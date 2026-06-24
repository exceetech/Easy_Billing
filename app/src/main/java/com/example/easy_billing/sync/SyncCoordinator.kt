package com.example.easy_billing.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-flight, fire-and-forget sync orchestrator.
 *
 * The contract:
 *
 *   • [requestSync] — call after every local write that may need
 *     to be propagated. If the device is online and no other sync
 *     is in flight, it pushes immediately; otherwise it returns
 *     fast and the row stays `is_synced = 0` for the next attempt.
 *
 *   • [flushPending] — same as [requestSync] but also pulls
 *     server-canonical state for the read-mostly tables
 *     (`store_info`, `billing_settings`). Called from the
 *     `DashboardActivity.onResume` hook so a user returning to the
 *     app gets a fresh, conflict-resolved view.
 *
 *   • [isOnline] — utility for callers that want to render
 *     "offline mode" hints without invoking a sync.
 *
 * Conflict handling:
 *   • For local-edited rows (GstProfile, StoreInfo, BillingSettings,
 *     bills, inventory, purchases), the upstream repositories use
 *     "latest updated_at wins". Pushes happen *before* pulls so the
 *     server has the user's most recent edit before any merge.
 *   • The single-flight [pushMutex] guarantees no two pushes for
 *     the same row run concurrently.
 *
 * Exceptions never escape — sync failures only flip `is_synced`
 * back to 0, never crash the caller.
 */
class SyncCoordinator private constructor(
    private val context: Context
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> t.printStackTrace() }
    )
    private val pushMutex = Mutex()
    @Volatile private var inFlight: Job? = null
    // Separate handle for flushPending so a push-only requestSync() in flight
    // can't be returned to a caller that asked for push + pull (Issue 12).
    @Volatile private var pullInFlight: Job? = null

    // Last time the full (expensive) pull cascade ran. Pulls re-download whole
    // tables, so we throttle them — pushes still run on every flushPending, but
    // the heavy pulls run at most once per [PULL_MIN_INTERVAL_MS] (Sync audit S5).
    @Volatile private var lastFullPullAt: Long = 0L

    // When true, ALL coordinator-driven sync is suspended. Used while a
    // destructive local operation (factory reset / workspace change) wipes the
    // database, so no background sync can write rows back into it mid-wipe.
    // Always paired: pauseSync() … resumeSync() (resume in a finally).
    @Volatile private var paused = false

    /* ------------------------------------------------------------------
     *  Public API
     * ------------------------------------------------------------------ */

    fun isOnline(): Boolean = isDeviceOnline(context)

    /**
     * Fire-and-forget. Pushes any pending local writes immediately
     * if the device is online; otherwise returns silently and lets
     * the rows stay pending for the next [flushPending] / network
     * callback / on-resume tick.
     *
     * Safe to call as often as you like — concurrent calls collapse
     * into the in-flight job.
     */
    fun requestSync(): Job {
        if (paused || !isOnline()) return scope.launch { /* paused or offline noop */ }
        // Check-and-assign must be atomic: without this guard two callers can
        // both see no active job and each launch one (Issue 5). syncAll() is
        // also globally mutex-guarded, so this is belt-and-braces.
        synchronized(this) {
            inFlight?.takeIf { it.isActive }?.let { return it }
            val job = scope.launch {
                pushMutex.withLock {
                    runCatching { SyncManager(context).syncAll() }
                }
            }
            inFlight = job
            return job
        }
    }

    /**
     * Like [requestSync], but also fans out the *pull* side of
     * SyncManager (store info, inventory). Use this on dashboard
     * resume so the user's freshly-foregrounded session reflects
     * any changes another device pushed while we were paused.
     */
    fun flushPending(force: Boolean = false): Job {
        if (paused || !isOnline()) return scope.launch { /* paused or offline noop */ }
        synchronized(this) {
            // Coalesce on the PULL handle (not inFlight): if a previous
            // flushPending is still running, share it — but never hand back a
            // push-only requestSync() job, which wouldn't run the pulls (Issue 12).
            pullInFlight?.takeIf { it.isActive }?.let { return it }
            val job = scope.launch {
                pushMutex.withLock {
                    runCatching {
                        val sm = SyncManager(context)
                        // Push first → so unsynced local edits beat the
                        // pull-side merge. Push always runs (it's cheap and it's
                        // the user's own data going up).
                        sm.syncAll()

                        // Pulls re-download whole tables, so throttle them: skip
                        // when the last full pull was very recent unless forced
                        // (e.g. an explicit pull-to-refresh). Sync audit S5.
                        val now = System.currentTimeMillis()
                        if (force || now - lastFullPullAt >= PULL_MIN_INTERVAL_MS) {
                            runCatching { sm.pullAccountsFromServer() }
                            runCatching { sm.pullInventory() }
                            runCatching { sm.pullPurchases() }
                            runCatching { sm.pullInventoryLogs() }
                            runCatching { sm.pullImportServices() }
                            runCatching { sm.pullPurchaseReturns() }
                            runCatching { sm.pullCreditNotes() }
                            runCatching { sm.pullPurchaseBatches() }
                            // Mirror bills from other terminals last — products it
                            // references are recovered by pullInventory above (R3).
                            runCatching { sm.pullBills() }
                            // Propagate voids made on other terminals.
                            runCatching { sm.pullBillCancellations() }
                            lastFullPullAt = now
                        }
                    }
                }
            }
            pullInFlight = job
            return job
        }
    }

    /**
     * Cancels any in-flight sync operation immediately.
     * Use this before wiping the database or returning to login
     * to prevent race conditions.
     */
    fun cancelSync() {
        inFlight?.cancel()
        inFlight = null
        pullInFlight?.cancel()
        pullInFlight = null
    }

    /**
     * Suspend all coordinator-driven sync and cancel anything in flight.
     * Call this BEFORE wiping the local database (factory reset / workspace
     * change) so a background sync can't write rows back mid-wipe. While
     * paused, [requestSync] and [flushPending] are no-ops.
     *
     * MUST be paired with [resumeSync] — call it in a `finally` so sync can
     * never get stuck off.
     */
    fun pauseSync() {
        paused = true
        cancelSync()
    }

    /** Re-enable sync after a [pauseSync]. */
    fun resumeSync() {
        paused = false
    }

    /* ------------------------------------------------------------------
     *  Connectivity utility
     * ------------------------------------------------------------------ */

    companion object {

        // Minimum gap between full pull cascades (Sync audit S5). Resumes inside
        // this window still push, but don't re-download every table.
        private const val PULL_MIN_INTERVAL_MS = 90_000L  // 90s

        @Volatile private var INSTANCE: SyncCoordinator? = null

        fun get(context: Context): SyncCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncCoordinator(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }

        /** Static helper for places that don't want to grab the singleton. */
        fun isDeviceOnline(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            // Require INTERNET capability only — NOT VALIDATED (R7). Some networks
            // (captive portals, slow/odd Wi-Fi, certain enterprise APNs) never report
            // VALIDATED, which previously made coordinator-triggered syncs no-op
            // forever. If the link turns out dead the sync call just fails and the
            // rows stay pending for the next attempt — strictly better than never
            // trying. (NetworkReceiver still probes the backend before its sync.)
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
