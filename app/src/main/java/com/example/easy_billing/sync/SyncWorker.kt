package com.example.easy_billing.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Durable background sync. Unlike the in-process 5-minute retry loop in
 * EasyBillingApp (which dies with the process), WorkManager re-runs this even
 * after the app is killed — so an offline sale eventually reaches the server
 * without the user reopening the app (Sync audit S3).
 *
 * The actual work is delegated to [SyncCoordinator.flushPending] so it shares
 * the single-flight lock with every other sync trigger (no double-push).
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            SyncCoordinator.get(applicationContext).flushPending().join()
            // Deliberately NOT calling BackendHealthStatus.report() here in either
            // direction. flushPending() wraps its push/pull calls in runCatching
            // internally (SyncCoordinator.kt) and never rethrows, so this .join()
            // completing is not a reliable signal of whether the sync — or the
            // server — actually succeeded. Reporting an optimistic report(true) here
            // (an earlier version of this fix did) could silently overwrite a
            // correct "unreachable" state set moments earlier by
            // NetworkReceiver.checkBackend() or SplashActivity's own direct,
            // honest server calls, which ARE reliable signals. Same reasoning
            // means the old catch block here could never usefully report(false)
            // either — flushPending() essentially never throws.
            Result.success()
        } catch (e: Exception) {
            // Transient (offline/server down) — let WorkManager back off and retry.
            Result.retry()
        }
    }
}
