package com.example.easy_billing.network

data class AddProductRequest(
    val name: String,
    val variant_name: String?,
    val unit: String,
    val price: Double
)