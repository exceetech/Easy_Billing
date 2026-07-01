package com.example.easy_billing.network

/**
 * Response DTO for GET /purchase-return/{shop_id}.
 * Pydantic `PurchaseReturnOut` returns `datetime` fields as ISO strings.
 */
data class PurchaseReturnResponseDto(
    val id: Int,
    val shop_id: Int,
    val shop_product_id: Int?,
    val product_name: String,
    val variant_name: String?,
    val hsn_code: String?,
    val quantity_returned: Double,
    val taxable_amount: Double,
    val invoice_value: Double,
    val cgst_percentage: Double,
    val sgst_percentage: Double,
    val igst_percentage: Double,
    val cgst_amount: Double,
    val sgst_amount: Double,
    val igst_amount: Double,
    val state: String,
    val supplier_gstin: String?,
    val supplier_name: String?,
    val is_credit: Boolean,
    val credit_account_id: Int?,
    val created_at: String?,
    
    // Debit Note / Credit Note fields (v25+)
    val note_number: String?,
    val note_date: String?,
    val note_type: String?,
    val original_invoice_id: Int?,
    val original_invoice_number: String?,
    val original_invoice_date: String?,
    val place_of_supply: String?,
    val supply_type: String?,
    val cess_amount: Double?,
    val tax_amount: Double?,
    val total_amount: Double?,
    val document_type: String?,
    val document_nature: String?,
    val document_series: String?,
    val pre_gst: String?,
    val reason_for_issuing_document: String?,
    val note_refund_voucher_value: Double?,
    val rate: Double?,
    val eligibility_for_itc: String?,
    val availed_itc_integrated_tax: Double?,
    val availed_itc_central_tax: Double?,
    val availed_itc_state_tax: Double?,
    val availed_itc_cess: Double?,
    val invoice_type: String?,
    val place_of_supply_code: String?
)
