package com.example.easy_billing.network

data class InventoryLogResponse(
    val product_id: Int,
    val type: String,
    val quantity: Double,
    val price: Double,
    val date: Long?
)
