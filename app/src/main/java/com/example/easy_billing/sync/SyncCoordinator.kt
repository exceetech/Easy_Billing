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
        if (!isOnline()) return scope.launch { /* offline noop */ }
        inFlight?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch {
            pushMutex.withLock {
                runCatching { SyncManager(context).syncAll() }
            }
        }
        inFlight = job
        return job
    }

    /**
     * Like [requestSync], but also fans out the *pull* side of
     * SyncManager (store info, inventory). Use this on dashboard
     * resume so the user's freshly-foregrounded session reflects
     * any changes another device pushed while we were paused.
     */
    fun flushPending(): Job {
        if (!isOnline()) return scope.launch { /* offline noop */ }
        inFlight?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch {
            pushMutex.withLock {
                runCatching {
                    val sm = SyncManager(context)
                    // Push first → so unsynced local edits beat the
                    // pull-side merge. Pull second → freshen the
                    // read-mostly tables.
                    sm.syncAll()
                    runCatching { sm.pullInventory() }
                }
            }
        }
        inFlight = job
        return job
    }

    /* ------------------------------------------------------------------
     *  Connectivity utility
     * ------------------------------------------------------------------ */

    companion object {

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
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
