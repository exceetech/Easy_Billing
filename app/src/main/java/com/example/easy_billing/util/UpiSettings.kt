package com.example.easy_billing.util

import android.content.Context

/** Shop owner's ON/OFF choice for accepting UPI payments (per device). */
object UpiSettings {
    private const val PREF = "app_settings"
    private const val KEY = "upi_enabled"

    fun isSet(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).contains(KEY)

    /** Until the owner makes a choice, UPI stays available (existing shops keep working). */
    fun isEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}
