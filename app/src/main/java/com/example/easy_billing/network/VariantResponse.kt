package com.example.easy_billing.network

data class VariantResponse(
    val id: Int,
    val product_id: Int,
    val variant_name: String,
    val unit: String
)
