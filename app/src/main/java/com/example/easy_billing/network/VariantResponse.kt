package com.example.easy_billing.network

data class VariantResponse(
    val id: Int,
    val product_id: Int,
    val variant_name: String,
    val unit: String,
    // Statutory autofill fields (price never included).
    val hsn_code: String? = null,
    val hsn_description: String? = null,
    val official_uqc: String? = null,
    val default_gst_rate: Double = 0.0,
    val cgst_percentage: Double = 0.0,
    val sgst_percentage: Double = 0.0,
    val igst_percentage: Double = 0.0,
    val cess_rate: Double = 0.0
)
