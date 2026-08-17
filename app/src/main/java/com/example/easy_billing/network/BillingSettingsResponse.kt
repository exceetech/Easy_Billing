package com.example.easy_billing.network

data class BillingSettingsResponse(
    val default_gst: Float,
    val printer_layout: String,
    // key_secret / webhook_secret are never returned by the backend —
    // only the public key id + a computed "is it set up" flag.
    val razorpay_key_id: String? = null,
    val razorpay_configured: Boolean = false
)
