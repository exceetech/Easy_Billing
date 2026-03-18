package com.example.easy_billing.util

import android.content.Context

object CurrencyUtils {

    fun format(context: Context, amount: Double): String {

        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val currency = prefs.getString("app_currency","₹ INR")

        val symbol = when(currency){
            "$ USD" -> "$"
            "€ EUR" -> "€"
            else -> "₹"
        }

        return "$symbol%.2f".format(amount)
    }
}