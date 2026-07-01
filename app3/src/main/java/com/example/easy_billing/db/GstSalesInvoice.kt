package com.example.easy_billing.db

import com.example.easy_billing.util.appNow

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Invoice-level row for the GST-aware billing flow.
 *
 *   • One row per generated invoice — mirrors the legacy [Bill]
 *     table but carries the extra B2B / B2C metadata required by
 *     the invoice header on the printed bill and by GSTR-1.
 *
 *   • [billId]      — FK back into the legacy `bills` table so
 *                     existing bill-history / reports / inventory
 *                     code keeps working unchanged.
 *
 *   • [gstScheme]   — "Composition Scheme" or "Normal GST Scheme".
 *                     For Composition the cgst/sgst/igst totals
 *                     stay zero and `grandTotal` == `subtotal`.
 *
 *   • Customer fields are mandatory only for B2B (validated at
 *     the UI layer).  All amounts are inclusive of two-decimal
 *     rounding done by [com.example.easy_billing.util.GstBillingCalculator].
 *
 *   • Sync is per-invoice — child rows in [GstSalesInvoiceItem]
 *     ride the same `pending` / `synced` cycle by linking on
 *     `gstInvoiceId` and being flushed in the same SyncManager
 *     pass.
 */
@Entity(
    tableName = "gst_sales_invoice_table",
    indices = [
        Index(value = ["bill_id"]),
        Index(value = ["shop_id"]),
        Index(value = ["sync_status"])
    ]
)
data class GstSalesInvoice(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "bill_id")
    val billId: Int,

    @ColumnInfo(name = "shop_id")
    val shopId: String = "",

    @ColumnInfo(name = "invoice_type")
    val invoiceType: String = "B2C",          // B2B / B2C

    @ColumnInfo(name = "gst_scheme")
    val gstScheme: String = "",               // Composition Scheme / Normal GST Scheme

    @ColumnInfo(name = "customer_name")
    val customerName: String? = null,

    @ColumnInfo(name = "business_name")
    val businessName: String? = null,

    @ColumnInfo(name = "customer_phone")
    val customerPhone: String? = null,

    @ColumnInfo(name = "customer_gst")
    val customerGst: String? = null,

    @ColumnInfo(name = "customer_state")
    val customerState: String? = null,

    val subtotal: Double = 0.0,

    @ColumnInfo(name = "total_cgst")
    val totalCgst: Double = 0.0,

    @ColumnInfo(name = "total_sgst")
    val totalSgst: Double = 0.0,

    @ColumnInfo(name = "total_igst")
    val totalIgst: Double = 0.0,

    @ColumnInfo(name = "total_tax")
    val totalTax: Double = 0.0,

    @ColumnInfo(name = "grand_total")
    val grandTotal: Double = 0.0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = appNow(),

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending",       // pending / synced / failed

    @ColumnInfo(name = "server_id")
    val serverId: Int? = null,

    // ── GSTR-1 fields (v23) ──────────────────────────────────────────

    /** Human-readable bill number (e.g. "INV-0042"). */
    @ColumnInfo(name = "invoice_number")
    val invoiceNumber: String = "",

    /** Epoch millis of the invoice date / bill date. */
    @ColumnInfo(name = "invoice_date")
    val invoiceDate: Long = 0L,

    /**
     * Reverse Charge Applicable?
     *   "Y" = Yes   "N" = No (default)
     * Source: InvoiceActivity GST Options section.
     */
    @ColumnInfo(name = "reverse_charge")
    val reverseCharge: String = "N",

    /**
     * GSTR-1 invoice type.
     * Allowed: "Regular" | "SEZ supplies with payment" |
     *          "SEZ supplies without payment" | "Deemed Exp"
     * Source: InvoiceActivity GST Options dropdown.
     */
    @ColumnInfo(name = "gstr_invoice_type")
    val gstrInvoiceType: String = "Regular",

    /**
     * 2-digit GST state code for GSTR-1 Place of Supply.
     * B2B: required.  B2C: falls back to shop state code.
     */
    @ColumnInfo(name = "customer_state_code")
    val customerStateCode: String? = null,

    /** E-Commerce Operator GSTIN (only when sale is via e-commerce). */
    @ColumnInfo(name = "ecommerce_gstin")
    val ecommerceGstin: String? = null,

    /** E-Commerce Operator Name (only when sale is via e-commerce). */
    @ColumnInfo(name = "ecommerce_operator_name")
    val ecommerceOperatorName: String? = null,

    /** Whether this invoice has been cancelled for GST reporting. */
    @ColumnInfo(name = "is_cancelled")
    val isCancelled: Boolean = false,

    /** Epoch millis when cancellation was recorded. */
    @ColumnInfo(name = "cancelled_at")
    val cancelledAt: Long? = null,

    // ── ECO GSTR-1 Fields (Table 14/15) ──────────────────────────────

    @ColumnInfo(name = "eco_nature_of_supply")
    val ecoNatureOfSupply: String? = null,

    @ColumnInfo(name = "eco_document_type")
    val ecoDocumentType: String? = null,

    @ColumnInfo(name = "eco_supplier_gstin")
    val ecoSupplierGstin: String? = null,

    @ColumnInfo(name = "eco_supplier_name")
    val ecoSupplierName: String? = null,

    @ColumnInfo(name = "eco_recipient_gstin")
    val ecoRecipientGstin: String? = null,

    @ColumnInfo(name = "eco_recipient_name")
    val ecoRecipientName: String? = null,

    @ColumnInfo(name = "eco_role")
    val ecoRole: String? = null,

    // ── DOCS Fields (Table 13) ───────────────────────────────────────

    @ColumnInfo(name = "document_type")
    val documentType: String = "Invoice",

    @ColumnInfo(name = "document_nature")
    val documentNature: String = "Invoices for outward supply",

    @ColumnInfo(name = "document_series")
    val documentSeries: String = "INV"
)
