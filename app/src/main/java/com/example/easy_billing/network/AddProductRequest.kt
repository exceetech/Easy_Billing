package com.example.easy_billing.network

data class AddProductRequest(

    val name: String,

    val variant_name: String?,

    val unit: String,

    val price: Double,

    val track_inventory: Boolean,

    val initial_stock: Double?,

    val cost_price: Double?,

    val hsn_code: String? = null,

    val default_gst_rate: Double? = 0.0

)