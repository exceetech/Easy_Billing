package com.example.easy_billing.network

/**
 * Full bill + tax-complete items for cross-terminal mirroring (Sync re-audit R3).
 * Returned by GET /bills/since?after_id=N. Items carry the GST/HSN/taxable fields
 * a sales-return / debit-note needs, so a return on a bill created on another
 * terminal computes correct GST.
 */
data class BillSyncDto(
    val bill_id: Int,
    val bill_number: String,
    val subtotal: Double = 0.0,
    val discount_amount: Double = 0.0,
    val gst_amount: Double = 0.0,
    val final_amount: Double = 0.0,
    val payment_method: String = "Cash",
    val invoice_type: String = "B2C",
    val customer_state: String? = null,
    val customer_state_code: String? = null,
    val supply_type: String = "intrastate",
    val cgst_amount: Double = 0.0,
    val sgst_amount: Double = 0.0,
    val igst_amount: Double = 0.0,
    val is_cancelled: Boolean = false,
    val cancelled_at: Long? = null,
    val created_at: String? = null,
    val items: List<BillItemSyncDto> = emptyList()
)

/** A cancelled bill echoed by GET /bills/cancellations for cross-terminal void propagation. */
data class BillCancellationDto(
    val bill_number: String,
    val cancelled_at: Long? = null,
    val updated_at: Long? = null   // server-set; cursor
)

data class BillItemSyncDto(
    val shop_product_id: Int,
    val product_name: String,
    val variant: String? = null,
    val unit: String = "unit",
    val quantity: Double = 0.0,
    val unit_price: Double = 0.0,
    val line_subtotal: Double = 0.0,
    val taxable_amount: Double = 0.0,
    val gst_rate: Double = 0.0,
    val cgst_amount: Double = 0.0,
    val sgst_amount: Double = 0.0,
    val igst_amount: Double = 0.0,
    val total_amount: Double = 0.0,
    val hsn_code: String = "",
    val discount_amount: Double = 0.0
)
