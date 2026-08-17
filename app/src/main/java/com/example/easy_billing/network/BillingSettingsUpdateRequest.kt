package com.example.easy_billing.network

data class BillingSettingsUpdateRequest(
    val default_gst: Float,
    val printer_layout: String,
    // All three optional/write-only — null means "leave what's already
    // saved alone", so a plain save of GST/printer settings never wipes
    // an already-connected Razorpay account. Only sent (non-null) when
    // the shop actually edits the Razorpay fields.
    val razorpay_key_id: String? = null,
    val razorpay_key_secret: String? = null,
    val razorpay_webhook_secret: String? = null
)
