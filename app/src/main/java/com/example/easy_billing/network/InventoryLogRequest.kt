package com.example.easy_billing.network

data class InventoryLogRequest(
    val product_id: Int,
    val type: String,
    val quantity: Double,
    val price: Double,
    val date: Long
)