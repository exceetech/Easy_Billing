package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One record per bill line-item with full GST breakdown.
 * Written atomically at bill creation time.
 * Used for GSTR-1, GSTR-3B, and HSN Summary reports.
 * Reports NEVER query raw bill_items — always query this table.
 */
@Entity(tableName = "gst_sales_records")
data class GstSalesRecord(

    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    val invoiceNumber: String,
    val invoiceDate: Long,             // epoch millis

    // Customer
    val customerType: String,          // B2B / B2C
    val customerGstin: String? = null, // only for B2B

    // Supply
    val placeOfSupply: String,         // 2-digit state code
    val supplyType: String,            // intrastate / interstate

    // Product
    val hsnCode: String,
    val productName: String,
    val quantity: Double,
    val unit: String = "piece",

    // Tax
    val taxableValue: Double,
    val gstRate: Double,               // e.g. 18.0
    val cgstAmount: Double = 0.0,
    val sgstAmount: Double = 0.0,
    val igstAmount: Double = 0.0,
    val totalAmount: Double,

    // Sync
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending",

    val deviceId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    // ── GSTR-1 enrichment fields (v23) ───────────────────────────────

    /** Customer display name (for report readability). */
    val customerName: String? = null,

    /** Business / trading name for B2B customers. */
    val businessName: String? = null,

    /** Customer phone number. */
    val customerPhone: String? = null,

    /** Human-readable state label (e.g. "Kerala"). */
    val customerState: String? = null,

    /** 2-digit GST state code for Place of Supply (e.g. "32"). */
    val customerStateCode: String? = null,

    /** Reverse Charge: "Y" or "N". */
    val reverseCharge: String = "N",

    /** GSTR invoice type (Regular / SEZ / Deemed Exp). */
    val gstrInvoiceType: String = "Regular",

    /** E-Commerce Operator GSTIN (nullable). */
    val ecommerceGstin: String? = null,

    /** E-Commerce Operator Name (nullable). */
    val ecommerceOperatorName: String? = null,

    /** Cess rate (%) for this line item. */
    val cessRate: Double = 0.0,

    /** Cess amount for this line item. */
    val cessAmount: Double = 0.0,

    /** GST Unit Quantity Code (NOS, KGS, LTR, …). */
    val uqc: String? = null,

    /** HSN description for GSTR-1 HSN summary. */
    val hsnDescription: String? = null,

    /** True when the parent invoice was cancelled. */
    @ColumnInfo(name = "is_cancelled")
    val isCancelled: Boolean = false,

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
    val ecoRole: String? = null
)
