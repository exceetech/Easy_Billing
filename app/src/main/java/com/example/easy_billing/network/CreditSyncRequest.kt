package com.example.easy_billing.network

data class CreditSyncRequest(
    val account_id: Int,
    val amount: Double,
    val type: String,
    val reference_invoice: String? = null
)