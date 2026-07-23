package com.example.easy_billing.network

/**
 * Sample backend payload (`GET /gst/lookup/{gstin}` and `GET /gst/profile`):
 *
 * ```json
 * {
 *   "gstin":             "29ABCDE1234F1Z5",
 *   "legal_name":        "ACME PRIVATE LIMITED",
 *   "trade_name":        "ACME Mart",
 *   "gst_scheme":        "REGULAR",
 *   "registration_type": "Regular",
 *   "state_code":        "29",
 *   "address":           "12, MG Road, Bengaluru, Karnataka 560001",
 *   "cgst_percentage":    9.0,
 *   "sgst_percentage":    9.0,
 *   "igst_percentage":   18.0,
 *   "sync_status":       "synced"
 * }
 * ```
 *
 * `address`, the three percentages and `sync_status` are nullable so
 * older backend responses (pre-rollout) still parse cleanly.
 */
data class GstProfileResponse(
    val gstin: String,
    val legal_name: String,
    val trade_name: String,
    val gst_scheme: String,
    val registration_type: String,
    val state_code: String,
    val address: String? = null,
    val sync_status: String = "synced"
)

data class GstProfileRequest(
    val gstin: String,
    val legal_name: String,
    val trade_name: String,
    val gst_scheme: String,
    val registration_type: String,
    val state_code: String,
    val address: String? = null
)

// GstSyncResponse / GstSaleRecordDto / GstSalesSyncRequest REMOVED
// (Report 3, C3) — payload types for the retired POST gst/sales/sync
// endpoint. gst_sales_invoice(+items) via CreateGstSalesInvoiceDto /
// GstSalesSyncBatchRequest is the sync path now.

data class HsnSummaryItem(
    val hsn_code: String,
    val description: String,
    val uom: String,
    val total_quantity: Double,
    val taxable_value: Double,
    val cgst_amount: Double,
    val sgst_amount: Double,
    val igst_amount: Double,
    val total_tax: Double
)

data class Gstr1B2BInvoice(
    val customer_gstin: String,
    val invoice_number: String,
    val invoice_date: String,
    val invoice_value: Double,
    val place_of_supply: String,
    val supply_type: String,
    val taxable_value: Double,
    val gst_rate: Double,
    val cgst: Double,
    val sgst: Double,
    val igst: Double
)

data class Gstr1B2CItem(
    val place_of_supply: String,
    val supply_type: String,
    val gst_rate: Double,
    val taxable_value: Double,
    val cgst: Double,
    val sgst: Double,
    val igst: Double
)

data class Gstr1Response(
    val period_start: String,
    val period_end: String,
    val b2b: List<Gstr1B2BInvoice>,
    val b2c: List<Gstr1B2CItem>,
    val hsn_summary: List<HsnSummaryItem>,
    val total_taxable_value: Double,
    val total_cgst: Double,
    val total_sgst: Double,
    val total_igst: Double
)




data class Gstr2B2bItem(
    val supplier_gstin: String,
    val invoice_number: String,
    val invoice_date: String,
    val invoice_value: Double,
    val place_of_supply: String,
    val reverse_charge: String,
    val invoice_type: String,
    val rate: Double,
    val taxable_value: Double,
    val igst: Double,
    val cgst: Double,
    val sgst: Double,
    val cess: Double,
    val itc_eligibility: String,
    val availed_itc_igst: Double,
    val availed_itc_cgst: Double,
    val availed_itc_sgst: Double,
    val availed_itc_cess: Double
)

data class Gstr2B2burItem(
    val supplier_name: String,
    val invoice_number: String,
    val invoice_date: String,
    val invoice_value: Double,
    val place_of_supply: String,
    val supply_type: String,
    val rate: Double,
    val taxable_value: Double,
    val igst: Double,
    val cgst: Double,
    val sgst: Double,
    val cess: Double,
    val itc_eligibility: String,
    val availed_itc_igst: Double,
    val availed_itc_cgst: Double,
    val availed_itc_sgst: Double,
    val availed_itc_cess: Double
)

data class Gstr2ImpsItem(
    val invoice_number: String,
    val invoice_date: String,
    val invoice_value: Double,
    val place_of_supply: String,
    val rate: Double,
    val taxable_value: Double,
    val igst: Double,
    val cess: Double,
    val itc_eligibility: String,
    val availed_itc_igst: Double,
    val availed_itc_cess: Double
)

data class Gstr2ImpgItem(
    val port_code: String,
    val bill_of_entry_number: String,
    val bill_of_entry_date: String,
    val bill_of_entry_value: Double,
    val document_type: String,
    val sez_supplier_gstin: String,
    val rate: Double,
    val taxable_value: Double,
    val igst: Double,
    val cess: Double,
    val itc_eligibility: String,
    val availed_itc_igst: Double,
    val availed_itc_cess: Double
)

data class Gstr2CdnrItem(
    val supplier_gstin: String,
    val note_number: String,
    val note_date: String,
    val invoice_number: String,
    val invoice_date: String,
    val pre_gst: String,
    val document_type: String,
    val reason: String,
    val supply_type: String,
    val note_value: Double,
    val rate: Double,
    val taxable_value: Double,
    val igst: Double,
    val cgst: Double,
    val sgst: Double,
    val cess: Double,
    val itc_eligibility: String,
    val availed_itc_igst: Double,
    val availed_itc_cgst: Double,
    val availed_itc_sgst: Double,
    val availed_itc_cess: Double
)

data class Gstr2CdnurItem(
    val note_number: String,
    val note_date: String,
    val invoice_number: String,
    val invoice_date: String,
    val pre_gst: String,
    val document_type: String,
    val reason: String,
    val supply_type: String,
    val invoice_type: String,
    val note_value: Double,
    val rate: Double,
    val taxable_value: Double,
    val igst: Double,
    val cgst: Double,
    val sgst: Double,
    val cess: Double,
    val itc_eligibility: String,
    val availed_itc_igst: Double,
    val availed_itc_cgst: Double,
    val availed_itc_sgst: Double,
    val availed_itc_cess: Double
)

data class Gstr2ExempItem(
    val description: String,
    val composition: Double,
    val nil_rated: Double,
    val exempted: Double,
    val non_gst: Double
)

data class Gstr2HsnsumItem(
    val hsn: String,
    val description: String,
    val uqc: String,
    val total_quantity: Double,
    val total_value: Double,
    val taxable_value: Double,
    val igst: Double,
    val cgst: Double,
    val sgst: Double,
    val cess: Double
)

data class Gstr2Response(
    val period_start: String,
    val period_end: String,
    val b2b: List<Gstr2B2bItem>,
    val b2bur: List<Gstr2B2burItem>,
    val imps: List<Gstr2ImpsItem>,
    val impg: List<Gstr2ImpgItem>,
    val cdnr: List<Gstr2CdnrItem>,
    val cdnur: List<Gstr2CdnurItem>,
    val exemp: List<Gstr2ExempItem>,
    val hsnsum: List<Gstr2HsnsumItem>
)

// Gstr3BSupplyDetail / Gstr3BResponse REMOVED — GSTR-3B not needed for this app.
