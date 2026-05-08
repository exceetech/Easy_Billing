package com.example.easy_billing.db

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
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending",       // pending / synced / failed

    @ColumnInfo(name = "server_id")
    val serverId: Int? = null
)
