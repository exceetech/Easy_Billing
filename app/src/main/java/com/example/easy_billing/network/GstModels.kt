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
    val total_tax: Double,
    // Table 12 columns the server port used to drop. Defaulted so an older
    // server still parses (values then fall back to the previous behaviour).
    val total_value: Double = 0.0,
    val cess_amount: Double = 0.0,
    val rate: Double = 0.0
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
    val igst: Double,
    // GSTR-1 Table 4 attributes. Defaulted so a server that predates this
    // change still parses (the values simply fall back to the old behaviour).
    val receiver_name: String = "",
    val reverse_charge: String = "N",
    val invoice_type: String = "Regular",
    val ecom_gstin: String = "",
    val cess_amount: Double = 0.0
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

data class Gstr1B2CLItem(
    val invoice_number: String,
    val invoice_date: String,
    val invoice_value: Double,
    val place_of_supply: String,
    val rate: Double,
    val taxable_value: Double,
    val cess_amount: Double = 0.0,
    val ecom_gstin: String = ""
)

data class Gstr1B2CSItem(
    val type: String = "OE",
    val place_of_supply: String,
    val rate: Double,
    val taxable_value: Double,
    val cess_amount: Double = 0.0,
    val ecom_gstin: String = ""
)

data class Gstr1CdnrItem(
    val customer_gstin: String,
    val receiver_name: String = "",
    val note_number: String,
    val note_date: String,
    val note_type: String,
    val place_of_supply: String,
    val reverse_charge: String = "N",
    val note_supply_type: String = "",
    val note_value: Double,
    val rate: Double,
    val taxable_value: Double,
    val cess_amount: Double = 0.0
)

data class Gstr1CdnurItem(
    val ur_type: String = "",
    val note_number: String,
    val note_date: String,
    val note_type: String,
    val place_of_supply: String,
    val note_value: Double,
    val rate: Double,
    val taxable_value: Double,
    val cess_amount: Double = 0.0
)

data class Gstr1DocsItem(
    val nature_of_document: String,
    val sr_from: String,
    val sr_to: String,
    val total_number: Int,
    val cancelled: Int = 0
)

data class Gstr1EcoItem(
    val nature_of_supply: String,
    val eco_gstin: String = "",
    val eco_name: String = "",
    val net_value: Double,
    val igst: Double = 0.0,
    val cgst: Double = 0.0,
    val sgst: Double = 0.0,
    val cess: Double = 0.0
)

data class Gstr1EcoB2BItem(
    val supplier_gstin: String = "",
    val supplier_name: String = "",
    val recipient_gstin: String,
    val recipient_name: String = "",
    val doc_number: String,
    val doc_date: String,
    val supply_value: Double,
    val place_of_supply: String,
    val doc_type: String = "Invoice",
    val rate: Double,
    val taxable_value: Double,
    val cess_amount: Double = 0.0
)

data class Gstr1EcoB2CItem(
    val supplier_gstin: String = "",
    val supplier_name: String = "",
    val place_of_supply: String,
    val rate: Double,
    val taxable_value: Double,
    val cess_amount: Double = 0.0
)

data class Gstr1EcoUrp2BItem(
    val recipient_gstin: String,
    val recipient_name: String = "",
    val doc_number: String,
    val doc_date: String,
    val supply_value: Double,
    val place_of_supply: String,
    val doc_type: String = "Invoice",
    val rate: Double,
    val taxable_value: Double,
    val cess_amount: Double = 0.0
)

data class Gstr1EcoUrp2CItem(
    val place_of_supply: String,
    val rate: Double,
    val taxable_value: Double,
    val cess_amount: Double = 0.0
)

data class Gstr1Response(
    val period_start: String,
    val period_end: String,
    val b2b: List<Gstr1B2BInvoice>,
    val b2c: List<Gstr1B2CItem> = emptyList(),
    val b2cl: List<Gstr1B2CLItem> = emptyList(),
    val b2cs: List<Gstr1B2CSItem> = emptyList(),
    val cdnr: List<Gstr1CdnrItem> = emptyList(),
    val cdnur: List<Gstr1CdnurItem> = emptyList(),
    val docs: List<Gstr1DocsItem> = emptyList(),
    val eco: List<Gstr1EcoItem> = emptyList(),
    val eco_b2b: List<Gstr1EcoB2BItem> = emptyList(),
    val eco_b2c: List<Gstr1EcoB2CItem> = emptyList(),
    val eco_urp2b: List<Gstr1EcoUrp2BItem> = emptyList(),
    val eco_urp2c: List<Gstr1EcoUrp2CItem> = emptyList(),
    val hsn_summary: List<HsnSummaryItem> = emptyList(),
    val hsn_b2b: List<HsnSummaryItem> = emptyList(),
    val hsn_b2c: List<HsnSummaryItem> = emptyList(),
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
    // Defaulted so an older server (which didn't send it) still parses.
    val reverse_charge: String = "N",
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
    // Rows group by (hsn, uqc, rate); defaulted so an older server still parses.
    val rate: Double = 0.0,
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
