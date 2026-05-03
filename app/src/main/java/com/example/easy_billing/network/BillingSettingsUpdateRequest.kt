package com.example.easy_billing.network

data class BillingSettingsUpdateRequest(
    val default_gst: Float,
    val printer_layout: String
)
