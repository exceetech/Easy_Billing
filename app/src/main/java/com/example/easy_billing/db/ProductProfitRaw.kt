package com.example.easy_billing.db

data class ProductProfitRaw(
    val productName: String,
    val variant: String?,
    val totalQty: Double,
    val revenue: Double,
    val cost: Double,
    val profit: Double
)