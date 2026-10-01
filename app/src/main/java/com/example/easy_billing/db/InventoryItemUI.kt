package com.example.easy_billing.db

data class InventoryItemUI(
    val productName: String,
    val variant: String?,
    val stock: Double,
    val avgCost: Double,
    val productId: Int,
    val category: String = "",
    val hsnCode: String? = null,
    val unit: String? = null,
    // Drives the "PURCHASED" / "ADDED BY YOU" tag on the inventory row —
    // mirrors Product.isPurchased so the two product kinds are visually
    // distinguishable (see InventoryAdapter.onBindViewHolder).
    val isPurchased: Boolean = false
)