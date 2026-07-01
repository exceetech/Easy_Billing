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
            Result.success()
        } catch (e: Exception) {
            // Transient (offline/server down) — let WorkManager back off and retry.
            Result.retry()
        }
    }
}
