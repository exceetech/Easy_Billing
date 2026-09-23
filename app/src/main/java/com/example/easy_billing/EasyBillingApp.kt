package com.example.easy_billing

import android.app.Application
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.sync.SyncCoordinator
import com.example.easy_billing.sync.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EasyBillingApp : Application() {

    // Process-lived scope. Cancelled implicitly when the OS kills the process.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Standard "is the app actually in the background" counter: goes 0→1
    // only when the FIRST Activity of a fresh foreground entry starts, and
    // back to 0 only when the LAST one stops — normal navigation between
    // this app's own Activities never touches 0, so it doesn't falsely
    // re-lock mid-navigation. See AppLockState's doc comment for why this
    // exists (Quick Unlock as a real lock screen, not just a re-login aid).
    private var startedActivityCount = 0

    private fun registerAppLockLifecycleTracking() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivityCount++
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    // The whole app just left the foreground — relock so
                    // the next time any Activity resumes, BaseActivity's
                    // lock check sends the user to the PIN/fingerprint
                    // screen first.
                    com.example.easy_billing.util.AppLockState.lock()
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    override fun onCreate() {
        super.onCreate()
        registerAppLockLifecycleTracking()
        RetrofitClient.setContext(this)
        // Phase 0: corrected (internet-anchored) clock + shop-timezone source.
        // Must be initialised before any timestamp is read.
        com.example.easy_billing.util.AppClock.init(this)
        com.example.easy_billing.util.AppTime.init(this)
        // Support/debugging breadcrumb trail — see UserEventLogger's doc
        // comment. Must be initialised before any UserEventLogger.log() call.
        com.example.easy_billing.util.UserEventLogger.init(this)
        startPeriodicSyncRetry()
        scheduleDurableSync()
        scheduleSessionTimeoutCheck()
    }

    /**
     * Durable, process-death-surviving retry (Sync audit S3). WorkManager runs
     * [com.example.easy_billing.sync.SyncWorker] roughly every 15 minutes while
     * online, even after the app is swiped away — so offline writes eventually
     * reach the server without the user reopening the app. Complements (doesn't
     * replace) the in-process loop below, which covers the foreground gap.
     */
    private fun scheduleDurableSync() {
        runCatching {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val request = androidx.work.PeriodicWorkRequestBuilder<
                com.example.easy_billing.sync.SyncWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).setConstraints(constraints).build()

            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "easybilling-durable-sync",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    /**
     * Closes the "app minimized (not killed) offline for 12+ hours" gap:
     * BaseActivity/SessionTimeoutGuard's timer only runs while a screen is
     * actually onResume()'d, so it never fires while the app just sits in
     * the background. Deliberately NO network constraint here — unlike
     * scheduleDurableSync() this job's whole job is to notice the device has
     * been offline, so it must still run while offline. WorkManager enforces
     * a 15-minute floor on periodic work; this runs hourly, which is fine
     * against a 12-hour timeout.
     */
    private fun scheduleSessionTimeoutCheck() {
        runCatching {
            val request = androidx.work.PeriodicWorkRequestBuilder<
                com.example.easy_billing.sync.SessionTimeoutWorker>(
                1, java.util.concurrent.TimeUnit.HOURS
            ).build()

            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "easybilling-session-timeout-check",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    /**
     * Lightweight safety net for the "online but idle on one screen" gap
     * (Issue 14): previously a failed/pending row was only retried when the
     * user navigated, a write happened, or the network toggled. This loop
     * nudges the coordinator every [RETRY_INTERVAL_MS] — but only when there
     * is actually something pending/failed, and only while online (the
     * coordinator no-ops offline). Work coalesces through the coordinator's
     * single-flight lock, so this never piles up concurrent syncs.
     *
     * NOTE: this runs only while the process is alive. For retries that
     * survive the app being killed, the durable upgrade is a WorkManager
     * periodic job — that needs the androidx.work dependency added.
     */
    private fun startPeriodicSyncRetry() {
        appScope.launch {
            var interval = BASE_RETRY_MS
            var lastFailed = -1
            while (isActive) {
                delay(interval)
                val status = SyncState.current
                val hasWork = status.pending > 0 || status.failed > 0 || status.blockedByProduct > 0
                if (hasWork) {
                    runCatching { SyncCoordinator.get(this@EasyBillingApp).requestSync() }

                    // Exponential backoff when the SAME failures persist across
                    // cycles — a permanently-failing row should stop hammering the
                    // server every 5 min (Sync audit S7). Any progress (failed
                    // count drops/changes, or only transient pending rows remain)
                    // resets to the base interval.
                    interval = if (status.failed > 0 && status.failed == lastFailed) {
                        (interval * 2).coerceAtMost(MAX_RETRY_MS)
                    } else {
                        BASE_RETRY_MS
                    }
                    lastFailed = status.failed
                } else {
                    interval = BASE_RETRY_MS
                    lastFailed = 0
                }
            }
        }
    }

    private companion object {
        const val BASE_RETRY_MS = 5 * 60 * 1000L    // 5 minutes
        const val MAX_RETRY_MS  = 60 * 60 * 1000L   // back off up to 1 hour
    }
}
