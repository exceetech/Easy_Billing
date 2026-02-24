package com.example.easy_billing.network

data class ShopProductResponse(
    val id: Int,
    val shop_id: Int,
    val name: String,
    val global_product_id: Int,
    val price: Double,
    val is_active: Boolean
)