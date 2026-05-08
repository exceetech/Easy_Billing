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
    val created_at: Long,
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
    val sales_igst_percentage: Double
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
    val created_at: Long
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
