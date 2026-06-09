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

    val default_gst_rate: Double? = 0.0,

    // Sales tax percentages stored on the product master
    val cgst_percentage: Double = 0.0,
    val sgst_percentage: Double = 0.0,
    val igst_percentage: Double = 0.0,

    // ── GSTR-1 product master fields (v23) ──
    val official_uqc: String? = null,
    val hsn_description: String? = null,
    val cess_rate: Double = 0.0,
    val supply_classification: String = "TAXABLE",

    val category: String = "",

    val is_purchased: Boolean = false

)