package com.example.easy_billing.network

/**
 * Network DTOs for Sales Return (Credit Note) sync.
 *
 * Follows the same pattern as [PurchaseSyncRequest] / [PurchaseSyncResponse]:
 *   • Client sends a batch of credit notes with their items.
 *   • Server echoes a local_id → server_id map used to mark rows synced.
 *   • Idempotent on note_number — re-pushing the same note number is safe.
 *
 * Endpoint: POST /credit-notes/sync
 */
data class CreditNoteSyncRequest(
    val credit_notes: List<CreditNoteDto>
)

data class CreditNoteDto(
    val local_id: Int,
    // Stable per-install id (Issue 10) — lets the server tell apart two
    // devices on the same shop that independently numbered a credit/debit
    // note the same way, instead of one silently overwriting the other.
    val client_device_id: String? = null,
    val note_number: String,
    val note_date: Long,
    val note_type: String,                  // "C" or "D"
    val note_supply_type: String,
    val original_invoice_id: Int,
    val original_invoice_number: String,
    val original_invoice_date: Long,
    val customer_name: String,
    val customer_gstin: String?,
    val place_of_supply: String,
    val reverse_charge: String,
    val supply_type: String,
    val ur_type: String,
    val document_type: String?,
    val document_nature: String?,
    val document_series: String?,
    val taxable_value: Double,
    val tax_amount: Double,
    val cess_amount: Double,
    val total_amount: Double,
    val cgst_amount: Double,
    val sgst_amount: Double,
    val igst_amount: Double,
    val created_at: Long,
    val items: List<CreditNoteItemDto>
)

data class CreditNoteItemDto(
    val product_id: Int,
    val product_name: String,
    val variant: String?,
    val hsn_code: String,
    val unit: String,
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

data class CreditNoteSyncResponse(
    val success_count: Int = 0,
    val note_id_map: Map<String, Int> = emptyMap(),   // "local_id" → server_id
    val message: String? = null
)
