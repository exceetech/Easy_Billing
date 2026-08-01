package com.example.easy_billing.util

import android.content.Context

/**
 * The one truly cross-thread-safe choke point for "clear the auth session."
 *
 * forceLogout()-equivalent logic exists in three places (BaseActivity,
 * SessionTimeoutGuard, SessionTimeoutWorker). The first two run on the Main
 * dispatcher (Activity lifecycleScope), where a plain
 * `if (TOKEN == null) return` guard is genuinely atomic — Kotlin's
 * cooperative single-thread scheduling means nothing can interleave between
 * the check and the clear. But SessionTimeoutWorker is a CoroutineWorker
 * with no dispatcher override, so it runs on WorkManager's own background
 * executor — a real, different OS thread. A plain boolean guard checked
 * independently on that thread and on the Main thread is NOT atomic across
 * the two: both could read TOKEN as non-null before either clears it.
 *
 * `synchronized` uses a JVM monitor, which enforces mutual exclusion across
 * actual OS threads regardless of which coroutine dispatcher/executor is
 * calling — unlike a kotlinx.coroutines Mutex, which only serializes
 * coroutines, not arbitrary threads. This closes the gap for real.
 */
object SessionClearGate {
    private val lock = Any()

    /**
     * Atomically clears the "auth" session (preserving DEVICE_ID, the bill
     * idempotency key) and the "sync_cursors" prefs, UNLESS another caller
     * already did so first.
     *
     * @return true if THIS call performed the clear (caller should proceed
     *   to show its toast / navigate to login); false if someone else
     *   already handled it moments earlier (caller should do nothing further).
     */
    fun clearIfNeeded(context: Context): Boolean {
        synchronized(lock) {
            val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            if (prefs.getString("TOKEN", null) == null) return false

            val deviceId = prefs.getString("DEVICE_ID", null)
            prefs.edit().clear().apply()
            deviceId?.let { prefs.edit().putString("DEVICE_ID", it).apply() }
            context.getSharedPreferences("sync_cursors", Context.MODE_PRIVATE)
                .edit().clear().apply()

            return true
        }
    }
}
