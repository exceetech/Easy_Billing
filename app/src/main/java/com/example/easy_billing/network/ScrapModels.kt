package com.example.easy_billing.network

/** One scrap entry as returned by GET /scrap/{shop_id}. */
data class ScrapOutDto(
    val id: Int,
    val shop_id: Int,
    val shop_product_id: Int? = null,
    val product_name: String,
    val variant_name: String? = null,
    val quantity: Double,
    val taxable_amount: Double,
    val invoice_value: Double,
    val reason: String? = null,
    val created_at: String
)
