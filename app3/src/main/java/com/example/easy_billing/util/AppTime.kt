package com.example.easy_billing.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Single source of truth for time on the client.
 *
 * Bills are stamped with — and reports are computed in — the SHOP's timezone
 * (the wall clock the cashier sees). A bill made at 2:00 is stored as 2:00 with
 * no conversion. Every date/time the client sends to the backend
 * (Bill.created_at, report range boundaries, "today") flows through this single
 * object so the whole app stays consistent.
 *
 * Two things this object guarantees (Phase 0):
 *  1. ZONE comes from a configurable shop timezone (default = device zone), so a
 *     travelling or misconfigured device can't silently shift every timestamp.
 *  2. "now" comes from [AppClock] (internet-anchored), NOT the raw device clock,
 *     so timestamps are correct even if the user has set the device clock wrong.
 *
 * NOTE for the backend: it stores Bill.created_at as a NAIVE timestamp and
 * interprets it in APP_TIMEZONE. Set the backend's APP_TIMEZONE env var to the
 * same zone configured here so reports line up exactly.
 */
object AppTime {

    private const val PREFS = "app_settings"
    private const val KEY_SHOP_TZ = "shop_timezone"

    @Volatile private var appContext: Context? = null

    /** Call once from Application.onCreate (alongside AppClock.init). */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** Persist the shop timezone id (e.g. "Asia/Kolkata"). Empty = follow device. */
    fun setShopTimeZone(id: String?) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_SHOP_TZ, id ?: "")?.apply()
    }

    /**
     * The timezone used for every date/time value — the configured shop zone,
     * falling back to the device's current zone when unset. Read fresh each time.
     */
    val ZONE: TimeZone
        get() {
            val id = appContext
                ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.getString(KEY_SHOP_TZ, "")
                ?.takeIf { it.isNotBlank() }
            return if (id != null) TimeZone.getTimeZone(id) else TimeZone.getDefault()
        }

    /** A Calendar positioned at corrected "now" in the shop timezone. */
    fun calendar(): Calendar = Calendar.getInstance(ZONE).apply { timeInMillis = AppClock.now() }

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

    // ── Convenience (all evaluated at corrected "now" in the app timezone) ──

    /** Today's date as "yyyy-MM-dd" in the app timezone. */
    fun todayIso(): String = isoDate().format(AppClock.nowDate())

    /** Current bill stamp "dd/MM/yyyy HH:mm" in the app timezone. */
    fun nowBillStamp(): String = billStamp().format(AppClock.nowDate())

    /** Current "yyyy-MM-dd'T'HH:mm:ss" in the app timezone. */
    fun nowIsoDateTime(): String = isoDateTime().format(AppClock.nowDate())
}
