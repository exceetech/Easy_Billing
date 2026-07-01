package com.example.easy_billing.network

/**
 * Sync DTOs for the new purchase / stock-out tables.
 *
 * The shapes follow the local Room entities verbatim so the backend
 * can mirror them 1:1 if it wants. `local_id` is included so the
 * server can echo back a `local_id → server_id` mapping in
 * [PurchaseSyncResponse], which the client uses to mark rows as
 * synced.
 *
 * Sample request body for `POST /purchases/sync`:
 *
 * ```json
 * {
 *   "purchases": [
 *     {
 *       "local_id": 12,
 *       "invoice_number": "INV-2026-0042",
 *       "supplier_gstin": "29ABCDE1234F1Z5",
 *       "supplier_name": "ACME Distributors",
 *       "state": "Karnataka",
 *       "taxable_amount": 1000.0,
 *       "cgst_percentage": 9.0,
 *       "sgst_percentage": 9.0,
 *       "igst_percentage": 0.0,
 *       "cgst_amount": 90.0,
 *       "sgst_amount": 90.0,
 *       "igst_amount": 0.0,
 *       "invoice_value": 1180.0,
 *       "items": [
 *         {
 *           "local_id": 31,
 *           "shop_product_id": 421,
 *           "product_name": "Lipton Tea 500g",
 *           "variant": null,
 *           "hsn_code": "0902",
 *           "quantity": 10.0,
 *           "unit": "piece",
 *           "taxable_amount": 1000.0,
 *           "invoice_value": 1180.0,
 *           "cost_price": 118.0,
 *           "purchase_cgst_percentage": 9.0,
 *           "purchase_sgst_percentage": 9.0,
 *           "purchase_igst_percentage": 0.0,
 *           "purchase_cgst_amount": 90.0,
 *           "purchase_sgst_amount": 90.0,
 *           "purchase_igst_amount": 0.0,
 *           "sales_cgst_percentage": 9.0,
 *           "sales_sgst_percentage": 9.0,
 *           "sales_igst_percentage": 0.0
 *         }
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * Server replies with:
 *
 * ```json
 * {
 *   "success_count": 1,
 *   "purchase_id_map": { "12": 5001 },
 *   "item_id_map":     { "31": 90234 },
 *   "message": "ok"
 * }
 * ```
 */
data class PurchaseSyncRequest(
    val purchases: List<PurchaseDto>
)

data class PurchaseDto(
    val local_id: Int,
    val invoice_number: String,
    val supplier_gstin: String?,
    val supplier_name: String,
    val state: String,
    val taxable_amount: Double,
    val cgst_percentage: Double,
    val sgst_percentage: Double,
    val igst_percentage: Double,
    val cgst_amount: Double,
    val sgst_amount: Double,
    val igst_amount: Double,
    val invoice_value: Double,
    /** Epoch millis of the date printed on the invoice. Nullable. */
    val invoice_date: Long? = null,
    val is_credit: Boolean = false,
    val credit_account_id: Int? = null,
    val created_at: Long,
    val place_of_supply_code: String = "",
    val reverse_charge: String = "N",
    val invoice_type: String = "Regular",
    val supply_type: String = "intrastate",
    val cess_paid: Double = 0.0,
    val eligibility_for_itc: String = "Inputs",
    val availed_itc_integrated_tax: Double = 0.0,
    val availed_itc_central_tax: Double = 0.0,
    val availed_itc_state_tax: Double = 0.0,
    val availed_itc_cess: Double = 0.0,
    val purchase_source: String = "DOMESTIC",
    val items: List<PurchaseItemDto>
)

data class PurchaseItemDto(
    val local_id: Int,
    val shop_product_id: Int?,
    val product_name: String,
    val variant: String?,
    val hsn_code: String?,
    val quantity: Double,
    val unit: String?,
    val taxable_amount: Double,
    val invoice_value: Double,
    val cost_price: Double,

    val purchase_cgst_percentage: Double,
    val purchase_sgst_percentage: Double,
    val purchase_igst_percentage: Double,
    val purchase_cgst_amount: Double,
    val purchase_sgst_amount: Double,
    val purchase_igst_amount: Double,

    val sales_cgst_percentage: Double,
    val sales_sgst_percentage: Double,
    val sales_igst_percentage: Double,

    val cess_percentage: Double = 0.0,
    val cess_amount: Double = 0.0,
    val eligibility_for_itc: String = "Inputs",
    val availed_itc_igst: Double = 0.0,
    val availed_itc_cgst: Double = 0.0,
    val availed_itc_sgst: Double = 0.0,
    val availed_itc_cess: Double = 0.0,
    val hsn_description: String = "",
    val official_uqc: String = "",
    val supply_classification: String = "TAXABLE"
)

data class PurchaseSyncResponse(
    val success_count: Int = 0,
    val purchase_id_map: Map<String, Int> = emptyMap(),
    val item_id_map: Map<String, Int> = emptyMap(),
    val message: String? = null
)

/* ---------- Purchase return ---------- */

data class PurchaseReturnSyncRequest(
    val records: List<PurchaseReturnDto>
)

data class PurchaseReturnDto(
    val local_id: Int,
    val shop_id: String,
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
    val is_credit: Boolean = false,
    val credit_account_id: Int? = null,
    val created_at: Long,
    // ── Debit Note fields (v25) ───────────────────────────────────────────────
    val note_number: String? = null,
    val note_date: Long? = null,
    val note_type: String? = null,              // "D"
    val original_invoice_id: Int? = null,
    val original_invoice_number: String? = null,
    val original_invoice_date: Long? = null,
    val place_of_supply: String = "",
    val supply_type: String = "intrastate",
    val cess_amount: Double = 0.0,
    val tax_amount: Double = 0.0,
    val total_amount: Double = 0.0,
    val document_type: String = "Debit Note",
    val document_nature: String? = null,
    val document_series: String? = null,
    val pre_gst: String = "N",
    val reason_for_issuing_document: String = "Purchase return",
    val note_refund_voucher_value: Double = 0.0,
    val rate: Double = 0.0,
    val eligibility_for_itc: String = "Inputs",
    val availed_itc_integrated_tax: Double = 0.0,
    val availed_itc_central_tax: Double = 0.0,
    val availed_itc_state_tax: Double = 0.0,
    val availed_itc_cess: Double = 0.0,
    val invoice_type: String = "Regular",
    val place_of_supply_code: String = ""
)

/* ---------- Scrap ---------- */

data class ScrapSyncRequest(
    val records: List<ScrapDto>
)

data class ScrapDto(
    val local_id: Int,
    val shop_id: String,
    val shop_product_id: Int?,
    val product_name: String,
    val variant_name: String?,
    val hsn_code: String?,
    val quantity: Double,
    val taxable_amount: Double,
    val invoice_value: Double,
    val cgst_percentage: Double,
    val sgst_percentage: Double,
    val igst_percentage: Double,
    val cgst_amount: Double,
    val sgst_amount: Double,
    val igst_amount: Double,
    val state: String,
    val reason: String,
    val created_at: Long
)

data class PurchaseResponse(
    val id: Int,
    val invoice_number: String,
    val supplier_gstin: String?,
    val supplier_name: String,
    val state: String,
    val taxable_amount: Double,
    val cgst_percentage: Double,
    val sgst_percentage: Double,
    val igst_percentage: Double,
    val cgst_amount: Double,
    val sgst_amount: Double,
    val igst_amount: Double,
    val invoice_value: Double,
    val invoice_date: Long? = null,
    val is_credit: Boolean = false,
    val credit_account_id: Int? = null,
    val created_at: Long,
    val place_of_supply_code: String = "",
    val reverse_charge: String = "N",
    val invoice_type: String = "Regular",
    val supply_type: String = "intrastate",
    val cess_paid: Double = 0.0,
    val eligibility_for_itc: String = "Inputs",
    val availed_itc_integrated_tax: Double = 0.0,
    val availed_itc_central_tax: Double = 0.0,
    val availed_itc_state_tax: Double = 0.0,
    val availed_itc_cess: Double = 0.0,
    val purchase_source: String = "DOMESTIC",
    val updated_at: Long? = null,   // server-set; delta-pull cursor
    val items: List<PurchaseItemResponse>
)

data class PurchaseItemResponse(
    val shop_product_id: Int?,
    val product_name: String,
    val variant: String?,
    val hsn_code: String?,
    val quantity: Double,
    val unit: String?,
    val taxable_amount: Double,
    val invoice_value: Double,
    val cost_price: Double,

    val purchase_cgst_percentage: Double,
    val purchase_sgst_percentage: Double,
    val purchase_igst_percentage: Double,
    val purchase_cgst_amount: Double,
    val purchase_sgst_amount: Double,
    val purchase_igst_amount: Double,

    val sales_cgst_percentage: Double,
    val sales_sgst_percentage: Double,
    val sales_igst_percentage: Double,

    val cess_percentage: Double = 0.0,
    val cess_amount: Double = 0.0,
    val eligibility_for_itc: String = "Inputs",
    val availed_itc_igst: Double = 0.0,
    val availed_itc_cgst: Double = 0.0,
    val availed_itc_sgst: Double = 0.0,
    val availed_itc_cess: Double = 0.0,
    val hsn_description: String = "",
    val official_uqc: String = "",
    val supply_classification: String = "TAXABLE"
)
