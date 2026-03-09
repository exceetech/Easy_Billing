package com.example.easy_billing.network

data class BillingSettingsUpdateRequest(
    val default_gst: Double,
    val printer_layout: String
)