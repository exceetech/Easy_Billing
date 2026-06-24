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

    override fun onCreate() {
        super.onCreate()
        RetrofitClient.setContext(this)
        // Phase 0: corrected (internet-anchored) clock + shop-timezone source.
        // Must be initialised before any timestamp is read.
        com.example.easy_billing.util.AppClock.init(this)
        com.example.easy_billing.util.AppTime.init(this)
        startPeriodicSyncRetry()
        scheduleDurableSync()
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
