package com.example.easy_billing.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Single source of truth for time on the client.
 *
 * Bills are stamped with — and reports are computed in — the DEVICE's own local
 * timezone (the wall clock the cashier sees). A bill made at 2:00 is stored as
 * 2:00 wherever the shop is, with no conversion. Every date/time the client
 * sends to the backend (Bill.created_at, report range boundaries, "today")
 * therefore flows through this single object so the whole app stays consistent.
 *
 * NOTE for the backend: it stores Bill.created_at as a NAIVE timestamp and
 * interprets it in APP_TIMEZONE. For reports to line up exactly, set the
 * backend's APP_TIMEZONE env var to the shop's actual timezone (the same zone
 * the device is set to).
 */
object AppTime {

    /**
     * The timezone used for every date/time value — the device's current zone.
     * Read fresh each time so it always follows the device setting.
     */
    val ZONE: TimeZone get() = TimeZone.getDefault()

    /** A Calendar positioned at "now" in the device timezone. */
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
