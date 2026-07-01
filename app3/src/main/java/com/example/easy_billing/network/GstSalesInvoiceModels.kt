package com.example.easy_billing.network

import com.example.easy_billing.util.appNow

/**
 * Wire-format DTOs for the GST-aware sales invoice batch sync.
 *
 *   • [CreateGstSalesItemDto]     — line item.
 *   • [CreateGstSalesInvoiceDto]  — invoice header + nested items.
 *   • [GstSalesSyncBatchRequest]  — what `/gst-sales/sync` consumes.
 *   • [GstSalesSyncBatchResponse] — what `/gst-sales/sync` returns.
 *   • [GstSalesInvoiceResponse]   — what `GET /gst-sales/{shop_id}` returns.
 *
 * Field names match `pos-backend/app/schemas/gst_sales_schema.py`
 * one-to-one. Keep them snake_case to mirror the Python side.
 */

data class CreateGstSalesItemDto(
    val product_id: Int,
    val product_name: String,
    val variant_name: String? = null,
    val hsn_code: String = "",
    val quantity: Double,
    val selling_price: Double,
    val taxable_amount: Double,
    val sales_cgst_percentage: Double = 0.0,
    val sales_sgst_percentage: Double = 0.0,
    val sales_igst_percentage: Double = 0.0,
    val cgst_amount: Double = 0.0,
    val sgst_amount: Double = 0.0,
    val igst_amount: Double = 0.0,
    val net_value: Double = 0.0,
    // ── GSTR-1 item fields (v23) ──
    val cess_rate: Double = 0.0,
    val cess_amount: Double = 0.0,
    val uqc: String? = null,
    val hsn_description: String? = null,
    val supply_classification: String = "TAXABLE"
)

data class CreateGstSalesInvoiceDto(
    val local_id: Int,
    val bill_id: Int,
    val invoice_type: String,            // B2B / B2C
    val gst_scheme: String,              // Composition Scheme / Normal GST Scheme
    val customer_name: String? = null,
    val business_name: String? = null,
    val customer_phone: String? = null,
    val customer_gst: String? = null,
    val customer_state: String? = null,
    val subtotal: Double,
    val total_cgst: Double,
    val total_sgst: Double,
    val total_igst: Double,
    val total_tax: Double,
    val grand_total: Double,
    val created_at: Long,
    val items: List<CreateGstSalesItemDto>,
    // ── GSTR-1 invoice fields (v23) ──
    val invoice_number: String = "",
    val invoice_date: Long = 0L,
    val reverse_charge: String = "N",
    val gstr_invoice_type: String = "Regular",
    val customer_state_code: String? = null,
    val ecommerce_gstin: String? = null,
    val ecommerce_operator_name: String? = null,
    val is_cancelled: Boolean = false,
    val cancelled_at: Long? = null,
    // ── ECO GSTR-1 Fields (Table 14/15) ──
    val eco_nature_of_supply: String? = null,
    val eco_document_type: String? = null,
    val eco_supplier_gstin: String? = null,
    val eco_supplier_name: String? = null,
    val eco_recipient_gstin: String? = null,
    val eco_recipient_name: String? = null,
    val eco_role: String? = null,
    // ── GSTR-1 DOCS Fields ──
    val document_type: String? = null,
    val document_nature: String? = null,
    val document_series: String? = null
)

/** Request body for cancelling a GST invoice. */
data class GstSalesCancelRequest(
    val invoice_number: String? = null,
    val local_id: Int? = null,
    val server_id: Int? = null,
    val cancelled_at: Long = appNow()
)

data class GstSalesCancelResponse(
    val success: Boolean = true,
    val message: String? = null
)

data class GstSalesSyncBatchRequest(
    val invoices: List<CreateGstSalesInvoiceDto>
)

data class GstSalesSyncBatchResponse(
    val success_count: Int = 0,
    val failed_count: Int = 0,
    val invoice_id_map: Map<String, Int> = emptyMap(), // local_id (string) → server_id
    val message: String? = null
)

data class GstSalesInvoiceItemResponse(
    val id: Int,
    val product_id: Int,
    val product_name: String,
    val variant_name: String? = null,
    val hsn_code: String = "",
    val quantity: Double,
    val selling_price: Double,
    val taxable_amount: Double,
    val sales_cgst_percentage: Double = 0.0,
    val sales_sgst_percentage: Double = 0.0,
    val sales_igst_percentage: Double = 0.0,
    val cgst_amount: Double = 0.0,
    val sgst_amount: Double = 0.0,
    val igst_amount: Double = 0.0,
    val net_value: Double = 0.0,
    val cess_rate: Double = 0.0,
    val cess_amount: Double = 0.0,
    val uqc: String? = null,
    val hsn_description: String? = null,
    val supply_classification: String = "TAXABLE"
)

data class GstSalesInvoiceResponse(
    val id: Int,
    val shop_id: Int,
    val bill_id: Int,
    val invoice_type: String,
    val gst_scheme: String,
    val customer_name: String? = null,
    val business_name: String? = null,
    val customer_phone: String? = null,
    val customer_gst: String? = null,
    val customer_state: String? = null,
    val subtotal: Double,
    val total_cgst: Double,
    val total_sgst: Double,
    val total_igst: Double,
    val total_tax: Double,
    val grand_total: Double,
    val created_at: String? = null,
    val items: List<GstSalesInvoiceItemResponse> = emptyList(),
    val invoice_number: String = "",
    val invoice_date: Long = 0L,
    val reverse_charge: String = "N",
    val gstr_invoice_type: String = "Regular",
    val customer_state_code: String? = null,
    val ecommerce_gstin: String? = null,
    val ecommerce_operator_name: String? = null,
    val is_cancelled: Boolean = false,
    val cancelled_at: Long? = null,
    val eco_nature_of_supply: String? = null,
    val eco_document_type: String? = null,
    val eco_supplier_gstin: String? = null,
    val eco_supplier_name: String? = null,
    val eco_recipient_gstin: String? = null,
    val eco_recipient_name: String? = null,
    val eco_role: String? = null,
    val document_type: String? = null,
    val document_nature: String? = null,
    val document_series: String? = null
)
