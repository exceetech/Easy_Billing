package com.example.easy_billing.network

data class SubscriptionResponse(
    val plan: String?,
    val expiry_date: String?,
    val remaining_days: Int,
    val status: String
)