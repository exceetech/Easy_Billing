package com.example.easy_billing.network

data class ShopSettingsUpdateRequest(
    val shop_name: String,
    val store_address: String,
    val phone: String,
    val store_gstin: String,
    val type: String
)