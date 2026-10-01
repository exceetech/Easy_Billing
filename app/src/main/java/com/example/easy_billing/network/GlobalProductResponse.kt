package com.example.easy_billing.network

data class GlobalProductResponse(
    val id: Int,
    val name: String,
    val is_verified: Boolean,
    val brand: String? = null,
    val variant_name: String? = null
)
