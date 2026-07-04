package com.example.easy_billing.util

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit

/**
 * The app's corrected clock — the single source of "now" for every business
 * timestamp (bills, sales, purchases, …). Instead of trusting the device wall
 * clock (which the user can change), it anchors to internet (NTP) time fetched
 * at login and then keeps time offline using the device's MONOTONIC elapsed-
 * realtime counter, which keeps ticking and can't be set by the user.
 *
 *   now() = ntpAtAnchor + (elapsedRealtime − anchorElapsed)
 *
 * Anchor is persisted, so after one successful online verification the app can
 * stamp correct timestamps offline. Until the first verification it falls back
 * to the raw device clock (and the login gate blocks billing in that state).
 *
 * Call [init] once from Application.onCreate before any timestamp is read.
 */
object AppClock {

    private const val PREFS = "clock"
    private const val KEY_NTP_AT_ANCHOR = "ntpAtAnchorMs"
    private const val KEY_ANCHOR_ELAPSED = "anchorElapsedMs"

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun prefs() =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Corrected current time in epoch-millis. */
    fun now(): Long {
        val p = prefs() ?: return System.currentTimeMillis()
        val ntpAtAnchor = p.getLong(KEY_NTP_AT_ANCHOR, 0L)
        val anchorElapsed = p.getLong(KEY_ANCHOR_ELAPSED, 0L)
        if (ntpAtAnchor <= 0L) return System.currentTimeMillis() // not yet verified
        val sinceAnchor = SystemClock.elapsedRealtime() - anchorElapsed
        return ntpAtAnchor + sinceAnchor
    }

    /** Corrected current time as a [java.util.Date]. */
    fun nowDate(): java.util.Date = java.util.Date(now())

    /**
     * Record a fresh, trusted internet time. Pins it to the monotonic clock so
     * subsequent [now] calls stay correct even offline and even if the user
     * changes the device wall clock afterwards.
     */
    fun anchor(ntpEpochMs: Long) {
        prefs()?.edit {
            putLong(KEY_NTP_AT_ANCHOR, ntpEpochMs)
            putLong(KEY_ANCHOR_ELAPSED, SystemClock.elapsedRealtime())
        }
    }

    /** True once we have at least one trusted anchor (offline billing allowed). */
    fun isVerified(): Boolean = (prefs()?.getLong(KEY_NTP_AT_ANCHOR, 0L) ?: 0L) > 0L

    /** Absolute difference (ms) between the raw device clock and corrected time. */
    fun driftFromDevice(): Long = kotlin.math.abs(now() - System.currentTimeMillis())
}

/** Top-level convenience used as the default for Room entity timestamps. */
fun appNow(): Long = AppClock.now()
