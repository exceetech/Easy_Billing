package com.example.easy_billing.network

// ================= REQUEST =================
data class CreateSaleRequest(
    val items: List<SaleItemDto>
)

// ================= ITEM =================
data class SaleItemDto(
    val product_id: Int,
    val quantity: Double,
    val selling_price: Double,
    val cost_price: Double,

    // 🔥 REQUIRED for backend grouping
    val product_name: String,
    val variant: String?
)