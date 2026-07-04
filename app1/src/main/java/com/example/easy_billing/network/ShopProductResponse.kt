package com.example.easy_billing.network

data class ShopProductResponse(
    val id: Int,
    val shop_id: Int? = null,
    val name: String,
    val variant: String?,
    val unit: String,
    val global_product_id: Int? = null,
    val price: Double,
    val is_active: Boolean = true,
    val hsn_code: String? = null,
    val default_gst_rate: Double? = 0.0,
    // Sales tax percentages
    val cgst_percentage: Double = 0.0,
    val sgst_percentage: Double = 0.0,
    val igst_percentage: Double = 0.0,
    // GSTR-1 product master fields (v23)
    val official_uqc: String? = null,
    val hsn_description: String? = null,
    val cess_rate: Double = 0.0,
    val category: String = "",
    val is_purchased: Boolean = false,
    val is_tax_inclusive: Boolean = false
)