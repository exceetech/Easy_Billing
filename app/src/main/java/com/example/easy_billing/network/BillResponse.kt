package com.example.easy_billing.network

data class BillResponse(
    val bill_id: Int,
    val bill_number: String,
    val total_amount: Double,
    val payment_method: String,
    val created_at: String
)