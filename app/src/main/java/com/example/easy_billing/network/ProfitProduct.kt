package com.example.easy_billing.network
data class ProfitProduct(
    // Used to drill the trend chart (ProfitChartActivity) down to one
    // product via GET /profit/trend?product_id=... Nullable for safety
    // against any older cached response shape.
    val product_id: Int? = null,
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