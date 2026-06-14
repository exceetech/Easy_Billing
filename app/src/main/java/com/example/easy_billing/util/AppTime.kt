package com.example.easy_billing.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Single source of truth for time on the client.
 *
 * Mirrors the backend's app/util/time_utils.py (APP_TIMEZONE, default
 * Asia/Kolkata). The backend stores Bill.created_at as a NAIVE wall-clock
 * timestamp in this timezone and computes every report's "today" / period
 * boundary in the same timezone. The client must therefore produce bill
 * timestamps and report date ranges in this SAME timezone — never the
 * device-local zone and never UTC — so the two always agree regardless of the
 * device's own timezone setting.
 *
 * If the backend's APP_TIMEZONE ever changes, update APP_TIMEZONE_ID here too.
 */
object AppTime {

    /** Must match the backend APP_TIMEZONE (app/util/time_utils.py). */
    const val APP_TIMEZONE_ID = "Asia/Kolkata"

    /** The app timezone used for every backend-facing date/time value. */
    val ZONE: TimeZone = TimeZone.getTimeZone(APP_TIMEZONE_ID)

    /** A Calendar positioned at "now" in the app timezone. */
    fun calendar(): Calendar = Calendar.getInstance(ZONE)

    /**
     * A SimpleDateFormat bound to the app timezone. Returns a fresh instance
     * each call (SimpleDateFormat is not thread-safe).
     */
    fun formatter(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = ZONE }

    // ── Common wire formats ────────────────────────────────────────────────

    /** "yyyy-MM-dd" — report range boundaries sent to the backend. */
    fun isoDate(): SimpleDateFormat = formatter("yyyy-MM-dd")

    /** "yyyy-MM-dd'T'HH:mm:ss" — Bill.created_at sent to the backend. */
    fun isoDateTime(): SimpleDateFormat = formatter("yyyy-MM-dd'T'HH:mm:ss")

    /** "dd/MM/yyyy HH:mm" — the human-readable bill date stored locally. */
    fun billStamp(): SimpleDateFormat = formatter("dd/MM/yyyy HH:mm")

    // ── Convenience (all evaluated "now" in the app timezone) ───────────────

    /** Today's date as "yyyy-MM-dd" in the app timezone. */
    fun todayIso(): String = isoDate().format(Date())

    /** Current bill stamp "dd/MM/yyyy HH:mm" in the app timezone. */
    fun nowBillStamp(): String = billStamp().format(Date())

    /** Current "yyyy-MM-dd'T'HH:mm:ss" in the app timezone. */
    fun nowIsoDateTime(): String = isoDateTime().format(Date())
}
