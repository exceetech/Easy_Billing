package com.example.easy_billing.util

import android.content.Context

object ShopManager {

    fun getShopId(context: Context): String {
        val prefs = context.getSharedPreferences("SHOP_PREFS", Context.MODE_PRIVATE)
        return prefs.getString("SHOP_ID", "EXE-0000")!!
    }
}