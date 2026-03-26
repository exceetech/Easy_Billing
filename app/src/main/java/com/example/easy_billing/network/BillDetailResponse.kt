package com.example.easy_billing.network

data class BillDetailResponse(
    val bill: BillResponse,
    val items: List<BillItemResponse>
)

data class BillItemResponse(
    val shop_product_id: Int,  // 🔥 ADD
    val product_name: String,
    val quantity: Int,
    val price: Double,
    val subtotal: Double
)