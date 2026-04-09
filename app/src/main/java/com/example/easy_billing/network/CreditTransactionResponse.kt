package com.example.easy_billing.network

data class CreditTransactionResponse(
    val id: Int,
    val account_id: Int,
    val amount: Double,
    val type: String,
    val created_at: String,

    val shop_id: Int
)
