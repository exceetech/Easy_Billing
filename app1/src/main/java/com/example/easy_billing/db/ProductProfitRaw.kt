package com.example.easy_billing.db

import java.io.Serializable

data class ProductProfitRaw(
    val productName: String,
    val variant: String?,
    val unit: String?,
    val totalQty: Double,
    val revenue: Double,
    val cost: Double,
    val profit: Double,
    val added: Double = 0.0,
    val sold: Double = 0.0,
    val remaining: Double = 0.0,
    val lossQty: Double = 0.0,
    val lossAmount: Double = 0.0
): Serializable