package com.example.easy_billing.network

data class InventoryLogResponse(
    val id: Int = 0,                // server-monotonic id; used as the delta cursor
    val product_id: Int,
    val type: String,
    val quantity: Double,
    val price: Double,
    val date: Long?
)
