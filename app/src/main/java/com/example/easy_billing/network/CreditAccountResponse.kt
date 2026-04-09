package com.example.easy_billing.network

data class CreditAccountResponse(
    val id: Int,
    val name: String,
    val phone: String,
    val due_amount: Double,

    val shop_id: Int,
    val is_active: Boolean
)
