package com.example.easy_billing.network

data class BillDetailResponse(
    val bill: BillResponse,
    val items: List<BillItemResponse>
)

data class BillItemResponse(
    val shop_product_id: Int,
    val product_name: String,
    val variant: String?,
    val unit: String?,
    val quantity: Double,
    val price: Double,
    val subtotal: Double
)