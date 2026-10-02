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
 * IMPORTANT — reboot handling: SystemClock.elapsedRealtime() resets to ~0 on
 * every device reboot, but the persisted [anchorElapsed] is from whatever boot
 * session was active when the anchor was last taken. A naive fix only catches
 * the case where elapsedRealtime() comes back SMALLER than the stale anchor
 * (a reboot that just happened). It misses the far more common case: the
 * device rebooted a while ago, and enough uptime has piled up since that
 * reboot that the fresh elapsedRealtime() counter has climbed back PAST the
 * old stale anchor value again — the comparison looks "normal" even though
 * anchorElapsed refers to a completely different boot session, so the
 * subtraction is comparing two unrelated counters and the result can be off
 * by an arbitrary, often large amount (this was the actual cause of bills
 * landing many days off, not just one day behind).
 *
 * The robust fix: derive an approximate "boot time" (wall-clock instant the
 * device booted) as deviceNow − elapsedRealtime, and persist that alongside
 * the anchor. If the boot time computed "now" doesn't match the boot time
 * recorded at the anchor (beyond a small tolerance for clock jitter), the
 * device rebooted at some point since — in ANY amount of time since, not
 * just immediately — so the elapsed-based projection is discarded in favor
 * of the device wall clock corrected by the drift measured at the last
 * anchor, which doesn't depend on the elapsed-time counter at all.
 *
 * Call [init] once from Application.onCreate before any timestamp is read.
 */
object AppClock {

    private const val PREFS = "clock"
    private const val KEY_NTP_AT_ANCHOR = "ntpAtAnchorMs"
    private const val KEY_ANCHOR_ELAPSED = "anchorElapsedMs"
    private const val KEY_DEVICE_WALL_AT_ANCHOR = "deviceWallAtAnchorMs"
    private const val KEY_BOOT_TIME_AT_ANCHOR = "bootTimeAtAnchorMs"

    /** Tolerance for treating two "boot time" estimates as the same boot
     *  session — covers normal clock jitter/measurement slack, not an actual
     *  reboot or a real wall-clock change. */
    private const val BOOT_TIME_TOLERANCE_MS = 10_000L

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun prefs() =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Approximate wall-clock instant the device last booted. */
    private fun estimatedBootTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** Corrected current time in epoch-millis. */
    fun now(): Long {
        val p = prefs() ?: return System.currentTimeMillis()
        val ntpAtAnchor = p.getLong(KEY_NTP_AT_ANCHOR, 0L)
        if (ntpAtAnchor <= 0L) return System.currentTimeMillis() // not yet verified

        val anchorElapsed = p.getLong(KEY_ANCHOR_ELAPSED, 0L)
        val bootTimeAtAnchor = p.getLong(KEY_BOOT_TIME_AT_ANCHOR, 0L)

        // Bug fix: a device that was already anchored BEFORE reboot-detection
        // was added has ntpAtAnchor/anchorElapsed from the old scheme but no
        // bootTimeAtAnchorMs yet (new key, default 0L). The old code treated
        // that missing key as "rebooted" unconditionally, which skipped the
        // NTP-corrected math entirely and fell through to the raw,
        // uncorrected device wall clock -- the exact problem this whole file
        // exists to prevent. On a device whose date/time was actually wrong,
        // every bill got silently stamped with the wrong day right after
        // this feature shipped, which is why bills stopped showing up under
        // "today" even though they synced fine. Until the next successful
        // online anchor() call (which now always persists bootTimeAtAnchorMs
        // — see below), fall back to the exact pre-reboot-detection
        // behavior instead of assuming a reboot happened.
        if (bootTimeAtAnchor <= 0L) {
            val sinceAnchor = SystemClock.elapsedRealtime() - anchorElapsed
            return ntpAtAnchor + sinceAnchor
        }

        val bootTimeNow = estimatedBootTime()
        val rebooted = kotlin.math.abs(bootTimeNow - bootTimeAtAnchor) > BOOT_TIME_TOLERANCE_MS

        if (rebooted) {
            // The device has rebooted (or had its wall clock changed) at some
            // point since the anchor — could be seconds or days ago, doesn't
            // matter. The elapsed-time counter from the anchor no longer
            // means anything, so fall back to the device wall clock corrected
            // by the drift we measured between NTP and the device clock at
            // the last anchor. This never depends on elapsedRealtime at all,
            // so it can't be thrown off by a reboot having happened.
            val deviceWallAtAnchor = p.getLong(KEY_DEVICE_WALL_AT_ANCHOR, 0L)
            if (deviceWallAtAnchor > 0L) {
                val driftAtAnchor = ntpAtAnchor - deviceWallAtAnchor
                return System.currentTimeMillis() + driftAtAnchor
            }
            return System.currentTimeMillis()
        }

        val sinceAnchor = SystemClock.elapsedRealtime() - anchorElapsed
        return ntpAtAnchor + sinceAnchor
    }

    /** Corrected current time as a [java.util.Date]. */
    fun nowDate(): java.util.Date = java.util.Date(now())

    /**
     * Record a fresh, trusted internet time. Pins it to the monotonic clock so
     * subsequent [now] calls stay correct even offline and even if the user
     * changes the device wall clock afterwards — and also pins the device wall
     * clock reading and the estimated boot time at this moment, so a reboot
     * (any time) before the next re-anchor can still be detected and
     * corrected (see [now]).
     */
    fun anchor(ntpEpochMs: Long) {
        prefs()?.edit {
            putLong(KEY_NTP_AT_ANCHOR, ntpEpochMs)
            putLong(KEY_ANCHOR_ELAPSED, SystemClock.elapsedRealtime())
            putLong(KEY_DEVICE_WALL_AT_ANCHOR, System.currentTimeMillis())
            putLong(KEY_BOOT_TIME_AT_ANCHOR, estimatedBootTime())
        }
    }

    /** True once we have at least one trusted anchor (offline billing allowed). */
    fun isVerified(): Boolean = (prefs()?.getLong(KEY_NTP_AT_ANCHOR, 0L) ?: 0L) > 0L

    /** Absolute difference (ms) between the raw device clock and corrected time. */
    fun driftFromDevice(): Long = kotlin.math.abs(now() - System.currentTimeMillis())
}

/** Top-level convenience used as the default for Room entity timestamps. */
fun appNow(): Long = AppClock.now()
