package com.example.easy_billing.network
data class ProfitProduct(
    val product_name: String,
    val variant: String?,
    val unit: String,
    val qty: Double,
    val revenue: Double,
    val cost: Double,
    val profit: Double,

    val added: Double,
    val sold: Double,
    val remaining: Double,
    val lossQty: Double,
    val lossAmount: Double
)