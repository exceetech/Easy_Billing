package com.example.easy_billing.network

/**
 * Response DTO for GET /credit-notes.
 * Pydantic `CreditNoteOut` returns `datetime` fields as ISO strings.
 */
data class CreditNoteResponseDto(
    val id: Int,
    val shop_id: Int,
    val local_id: Int?,
    val note_number: String,
    val note_date: String?,
    val note_type: String,
    val note_supply_type: String?,
    val original_invoice_id: Int?,
    val original_invoice_number: String?,
    val original_invoice_date: String?,
    val customer_name: String?,
    val customer_gstin: String?,
    val place_of_supply: String?,
    val reverse_charge: String,
    val supply_type: String,
    val ur_type: String?,
    val document_type: String?,
    val document_nature: String?,
    val document_series: String?,
    val taxable_value: Double,
    val cgst_amount: Double,
    val sgst_amount: Double,
    val igst_amount: Double,
    val cess_amount: Double,
    val tax_amount: Double,
    val total_amount: Double,
    val sync_status: String,
    val created_at: String?,
    val updated_at: String?,
    val items: List<CreditNoteItemResponseDto> = emptyList()
)

data class CreditNoteItemResponseDto(
    val id: Int,
    val note_id: Int,
    val product_id: Int?,
    val product_name: String,
    val variant: String?,
    val hsn_code: String?,
    val unit: String?,
    val quantity_sold: Double,
    val quantity_returned: Double,
    val rate: Double,
    val cost_price_used: Double,
    val taxable_value: Double,
    val gst_rate: Double,
    val cgst_amount: Double,
    val sgst_amount: Double,
    val igst_amount: Double,
    val cess_amount: Double,
    val tax_amount: Double,
    val total_amount: Double,
    val original_bill_item_id: Int?
)
