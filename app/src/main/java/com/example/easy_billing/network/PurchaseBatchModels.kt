package com.example.easy_billing.network

data class PurchaseBatchSyncRequest(
    val batches: List<PurchaseBatchDto>
)

data class PurchaseBatchDto(
    val local_id: Int,
    val product_id: Int,
    val purchase_invoice_id: Int?,
    val supplier_name: String?,
    val supplier_gstin: String?,
    val invoice_number: String?,
    val batch_code: String?,
    val quantity_purchased: Double,
    val quantity_remaining: Double,
    val unit_cost_excluding_tax: Double,
    val gst_percent: Double,
    val cgst_percent: Double,
    val sgst_percent: Double,
    val igst_percent: Double,
    val invoice_value: Double,
    val taxable_value: Double,
    val invoice_date: Long?,
    val created_at: Long?
)

data class PurchaseBatchSyncResponse(
    val success_count: Int = 0,
    val batch_id_map: Map<String, Int> = emptyMap(),
    val message: String? = null
)

data class PurchaseBatchResponseDto(
    val id: Int,
    val local_id: Int,
    val product_id: Int,
    val purchase_invoice_id: Int?,
    val supplier_name: String?,
    val supplier_gstin: String?,
    val invoice_number: String?,
    val batch_code: String?,
    val quantity_purchased: Double,
    val quantity_remaining: Double,
    val unit_cost_excluding_tax: Double,
    val gst_percent: Double,
    val cgst_percent: Double,
    val sgst_percent: Double,
    val igst_percent: Double,
    val invoice_value: Double,
    val taxable_value: Double,
    val invoice_date: String?,
    val created_at: String?
)
