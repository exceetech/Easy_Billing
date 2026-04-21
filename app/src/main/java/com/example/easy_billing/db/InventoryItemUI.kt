package com.example.easy_billing.db

data class InventoryItemUI(
    val productName: String,
    val variant: String?,
    val stock: Double,
    val avgCost: Double,
    val productId: Int
)