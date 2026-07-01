package com.example.easy_billing.util

import android.content.Context
import java.text.NumberFormat
import java.util.Locale

object CurrencyHelper {

    fun getCurrencySymbol(context: Context): String {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        // ✅ Now we store only symbol
        return prefs.getString("app_currency", "₹") ?: "₹"
    }

    fun format(context: Context, amount: Double): String {

        val symbol = getCurrencySymbol(context)

        // ✅ Indian format for ₹, normal for others
        val formatter = if (symbol == "₹") {
            NumberFormat.getNumberInstance(Locale("en", "IN"))
        } else {
            NumberFormat.getNumberInstance(Locale.US)
        }

        val formatted = formatter.format(amount)

        return "$symbol$formatted"
    }

    /**
     * Same locale-aware formatting as [format] but *without* the
     * currency symbol. Used in tabular layouts (e.g. the invoice
     * line-item list) where the column header already declares
     * the unit (e.g. "TOTAL (₹)") and repeating the symbol on
     * every cell wastes horizontal space.
     */
    fun formatNoSymbol(context: Context, amount: Double): String {
        val symbol = getCurrencySymbol(context)
        val formatter = if (symbol == "₹") {
            NumberFormat.getNumberInstance(Locale("en", "IN"))
        } else {
            NumberFormat.getNumberInstance(Locale.US)
        }
        return formatter.format(amount)
    }
}