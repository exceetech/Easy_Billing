package com.example.easy_billing.network


data class ShopSettingsResponse(
    val shop_name: String?,
    val store_address: String?,
    val phone: String?,
    val store_gstin: String?,
    val type: String?,

    val legal_name: String? = "",
    val trade_name: String? = "",
    val gst_scheme: String? = "",
    val state_code: String? = "",
    val registration_type: String? = "",
    val gst_sync_status: String? = "pending"
)