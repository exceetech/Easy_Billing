package com.example.easy_billing.network

data class CreditAccountResponse(
    val id: Int,
    val name: String,
    val phone: String,
    val due_amount: Double
)
