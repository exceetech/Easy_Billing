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

data class GstSyncResponse(
    val success_count: Int,
    val message: String
)

data class GstSaleRecordDto(
    val record_id: String,
    val invoice_number: String,
    val invoice_date: Long,
    val customer_type: String,
    val customer_gstin: String?,
    val place_of_supply: String,
    val supply_type: String,
    val total_invoice_value: Double,
    val taxable_value: Double,
    val cgst_amount: Double,
    val sgst_amount: Double,
    val igst_amount: Double,
    val cess_amount: Double,
    val hsn_code: String,
    val gst_rate: Double,
    val device_id: String
)

data class GstPurchaseRecordDto(
    val record_id: String,
    val vendor_gstin: String?,
    val vendor_name: String?,
    val invoice_number: String,
    val invoice_date: Long,
    val total_invoice_value: Double,
    val taxable_value: Double,
    val cgst_amount: Double,
    val sgst_amount: Double,
    val igst_amount: Double,
    val cess_amount: Double,
    val hsn_code: String,
    val gst_rate: Double,
    val itc_eligibility: String
)

data class GstSalesSyncRequest(
    val records: List<GstSaleRecordDto>
)

data class GstPurchaseSyncRequest(
    val records: List<GstPurchaseRecordDto>
)

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

data class Gstr2Item(
    val supplier_gstin: String?,
    val invoice_number: String,
    val invoice_date: String,
    val expense_type: String,
    val hsn_sac_code: String,
    val description: String,
    val taxable_value: Double,
    val gst_rate: Double,
    val cgst: Double,
    val sgst: Double,
    val igst: Double,
    val total: Double
)

data class Gstr2Response(
    val period_start: String,
    val period_end: String,
    val records: List<Gstr2Item>,
    val total_taxable_value: Double,
    val total_itc_cgst: Double,
    val total_itc_sgst: Double,
    val total_itc_igst: Double
)

data class Gstr3BSupplyDetail(
    val total_taxable_value: Double,
    val total_cgst: Double,
    val total_sgst: Double,
    val total_igst: Double,
    val total_cess: Double
)

data class Gstr3BResponse(
    val period_start: String,
    val period_end: String,
    val outward_taxable_supplies: Gstr3BSupplyDetail,
    val outward_zero_rated: Gstr3BSupplyDetail,
    val outward_nil_rated: Gstr3BSupplyDetail,
    val inward_nil_exempt: Gstr3BSupplyDetail,
    val itc_available: Gstr3BSupplyDetail,
    val net_tax_payable_cgst: Double,
    val net_tax_payable_sgst: Double,
    val net_tax_payable_igst: Double
)
