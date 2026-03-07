package com.example.easy_billing.network

data class CreateBillRequest(
    val bill_number: String,
    val items: List<BillItemRequest>,
    val payment_method: String,
    val gst: Double,
    val discount: Double,
    val total_amount: Double
)

data class BillItemRequest(
    val shop_product_id: Int,
    val quantity: Int
)