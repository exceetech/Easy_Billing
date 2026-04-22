package com.example.easy_billing.network

data class InventoryResponse(
    val product_id: Int,
    val stock: Double,
    val avg_cost: Double,
    val is_active: Boolean
)