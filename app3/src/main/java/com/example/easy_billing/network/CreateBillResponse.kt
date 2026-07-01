package com.example.easy_billing.network

data class CreateBillResponse(
    val message: String,
    val bill_id: Int,
    val bill_number: String,
    val total_amount: Double
)