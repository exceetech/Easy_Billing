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
}