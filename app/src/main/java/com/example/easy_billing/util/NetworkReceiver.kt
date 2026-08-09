package com.example.easy_billing.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.widget.Toast
import com.example.easy_billing.MainActivity
import com.example.easy_billing.R
import com.example.easy_billing.sync.SyncManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for "network regained" and runs a sync pass.
 *
 * Lifecycle hygiene (Issue 17):
 *   • Uses the APPLICATION context, never the Activity that created it, so the
 *     long-lived NetworkManager callback can't leak an Activity.
 *   • Registers the callback exactly ONCE per process. DashboardActivity calls
 *     startListening() on every onResume; previously each call registered a new
 *     callback (a leak + duplicate sync triggers). The [registered] guard makes
 *     repeated calls no-ops.
 *   • [isSyncing] is an AtomicBoolean so the cross-thread check-and-set is safe.
 */
class NetworkReceiver(context: Context) {

    private val appCtx = context.applicationContext

    fun startListening() {
        // Register at most once for the whole process.
        if (!registered.compareAndSet(false, true)) return

        val connectivityManager =
            appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {

                    // Collapse overlapping triggers into one in-flight sync.
                    if (!isSyncing.compareAndSet(false, true)) return

                    scope.launch {
                        try {
                            delay(1500)

                            when (BackendReachabilityChecker.check(appCtx)) {
                                BackendReachabilityChecker.Status.UNAUTHORIZED -> {
                                    // Token is genuinely invalid (401/403) → log out.
                                    forceLogout("Session expired")
                                    return@launch
                                }
                                BackendReachabilityChecker.Status.UNREACHABLE -> {
                                    // The device HAS internet (checked inside
                                    // BackendReachabilityChecker.check()) but OUR server specifically
                                    // didn't respond OK across 2 attempts. Do NOT log
                                    // out — pending rows stay put and are retried on
                                    // the next sync trigger. BackendHealthStatus was
                                    // already updated inside BackendReachabilityChecker.check(); the
                                    // persistent dashboard banner picks that up, so
                                    // this Toast is just an immediate nudge, not the
                                    // only signal (that used to be the only signal,
                                    // and easy to miss).
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(appCtx, appCtx.getString(R.string.server_not_reachable_toast), Toast.LENGTH_SHORT).show()
                                    }
                                    return@launch
                                }
                                BackendReachabilityChecker.Status.NO_INTERNET -> {
                                    // Device itself has no real internet — a different
                                    // concern (SessionTimeoutGuard/BaseActivity's
                                    // offline-session-timeout already owns this). Don't
                                    // report it as a backend problem and don't touch
                                    // BackendHealthStatus; the server was never the
                                    // thing that failed here.
                                    return@launch
                                }
                                BackendReachabilityChecker.Status.OK -> { /* proceed to sync below */ }
                            }

                            // ✅ BACKEND OK → SYNC
                            // Route through the coordinator's single-flight lock so a
                            // network-regain sync never races a write-triggered sync and
                            // double-pushes non-bill rows (Sync audit S1).
                            com.example.easy_billing.sync.SyncCoordinator
                                .get(appCtx)
                                .flushPending()
                                .join()

                            // Deliberately NOT calling BackendHealthStatus.report(true)
                            // here. flushPending() wraps its push/pull calls in
                            // runCatching internally (SyncCoordinator.kt) and never
                            // rethrows, so this line is reached whether the sync
                            // actually succeeded or silently failed against a down
                            // server — an earlier version of this fix called
                            // report(true) unconditionally right here, which could
                            // clobber the correct report(false) that BackendReachabilityChecker.check()
                            // just made moments earlier with a false "all clear."
                            // BackendReachabilityChecker.check()'s own direct getSubscription() call above
                            // is what actually verifies reachability; this line has no
                            // reliable signal to add to that.

                        } catch (e: Exception) {
                            e.printStackTrace()
                            when {
                                // 409 WORKSPACE_CHANGED: WorkspaceInterceptor already launched
                                // WorkspaceChangedActivity. Don't forceLogout (race condition).
                                e is retrofit2.HttpException && e.code() == 409 -> return@launch
                                // Genuine auth failure → log out.
                                e is retrofit2.HttpException && (e.code() == 401 || e.code() == 403) ->
                                    forceLogout("Session expired")
                                // Anything else (timeout, 5xx, parse, offline) is transient:
                                // never log the user out for it. Unsynced rows remain pending
                                // and retry on the next trigger. Only report this to
                                // BackendHealthStatus if the device genuinely has internet —
                                // otherwise this is the device-offline case, not a server
                                // problem, and reporting it here would mislabel it.
                                else -> {
                                    android.util.Log.w(
                                        "NetworkReceiver",
                                        "Transient sync error, will retry: ${e.message}"
                                    )
                                    if (NetworkUtils.isOnline(appCtx)) {
                                        BackendHealthStatus.report(false)
                                    }
                                }
                            }
                        } finally {
                            isSyncing.set(false)
                        }
                    }
                }
            }
        )
    }

    // ================= FORCE LOGOUT =================

    private fun forceLogout(reason: String) {

        // This ran on its own raw clear+restore pattern, missed when
        // BaseActivity/SessionTimeoutGuard/SessionTimeoutWorker were migrated
        // to SessionClearGate. That migration's whole point was closing races
        // between logout paths running on different thread contexts —
        // BaseActivity/SessionTimeoutGuard on Main, SessionTimeoutWorker on
        // WorkManager's background executor, and THIS class on its own
        // `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (a fourth,
        // independent thread context). Leaving this one on the old unguarded
        // pattern reopened exactly that race: a network-regain event here and
        // the offline-session-timeout loop discovering the same invalid token
        // at nearly the same moment could both clear+restore DEVICE_ID and
        // both fire a toast/navigate. SessionClearGate's `synchronized` gate
        // is what actually closes this across all four contexts.
        if (!SessionClearGate.clearIfNeeded(appCtx)) return

        scope.launch(Dispatchers.Main) {
            Toast.makeText(appCtx, reason, Toast.LENGTH_LONG).show()

            val intent = Intent(appCtx, MainActivity::class.java)
            intent.flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

            appCtx.startActivity(intent)
        }
    }

    private companion object {
        /** One process-wide scope instead of an ad-hoc one per callback. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** True once the single network callback has been registered. */
        private val registered = AtomicBoolean(false)

        /** Guards against overlapping sync passes from rapid network flaps. */
        private val isSyncing = AtomicBoolean(false)
    }
}
