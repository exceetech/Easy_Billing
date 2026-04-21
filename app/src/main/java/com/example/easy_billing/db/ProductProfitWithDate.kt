package com.example.easy_billing.db

data class ProductProfitWithDate(
    val productName: String,
    val variant: String?,
    val totalQty: Double,
    val revenue: Double,
    val cost: Double,
    val profit: Double,
    val billDate: String
)