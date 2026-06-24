package com.example.easy_billing.network

data class SubscriptionResponse(
    val plan: String?,
    val expiry_date: String?,
    // UTC instant (epoch-millis) from the backend; render in the shop timezone.
    // Preferred over expiry_date, which is a bare UTC string the old code
    // mis-rendered as device-local (off-by-a-day near midnight).
    val expiry_ms: Long? = null,
    val remaining_days: Int,
    val status: String
)