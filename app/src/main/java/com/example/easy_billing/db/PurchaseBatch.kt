package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single purchase batch (`purchase_batches`).
 *
 * Each batch is the durable record of one stock-in event — a single
 * line on a supplier's invoice or, for legacy / manual flows, a
 * synthetic placeholder seeded from the existing inventory row.
 *
 * The hybrid valuation architecture (v21) uses these rows for:
 *   • supplier returns valued at the batch's *own* unit cost
 *   • FIFO internal consumption when stock leaves (sale, scrap, …)
 *   • the source of truth for the weighted average — the inventory
 *     row's `averageCost` is derived as
 *       SUM(remainingQty × unitCostExcludingTax) / SUM(remainingQty)
 *
 * IMPORTANT: [unitCostExcludingTax] is always net of GST. For GST
 * purchases it is computed as `taxableValue / quantity`, NEVER as
 * `invoiceValue / quantity`. Direct add-stock flows (no GST split)
 * use the cost price as-is.
 */
@Entity(
    tableName = "purchase_batches",
    indices = [
        Index(value = ["productId"]),
        Index(value = ["purchaseInvoiceId"]),
        Index(value = ["is_synced"])
    ]
)
data class PurchaseBatch(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** Local product reference. */
    val productId: Int,

    /**
     * Local `purchase_table.id` this batch came from. Nullable so we
     * can also represent manual / migration batches that did not flow
     * through PurchaseRepository.
     */
    val purchaseInvoiceId: Int? = null,

    val supplierName: String? = null,
    val supplierGstin: String? = null,
    val invoiceNumber: String? = null,

    /** Optional supplier-side batch / lot code (e.g. expiry tracking). */
    val batchCode: String? = null,

    /** Original purchased quantity — never mutated after insert. */
    val quantityPurchased: Double,

    /**
     * Quantity still on the shelf for THIS batch. Decremented by FIFO
     * consumption (sales, scrap, …) and by explicit per-batch supplier
     * returns. Must never go negative.
     */
    val quantityRemaining: Double,

    /**
     * Cost per unit, EXCLUDING GST. Source of truth for batch-aware
     * valuation. See the class-level note above for the formula.
     */
    @ColumnInfo(name = "unit_cost_excluding_tax")
    val unitCostExcludingTax: Double,

    /* ─── GST snapshot at purchase time ─── */

    @ColumnInfo(name = "gst_percent")  val gstPercent: Double = 0.0,
    @ColumnInfo(name = "cgst_percent") val cgstPercent: Double = 0.0,
    @ColumnInfo(name = "sgst_percent") val sgstPercent: Double = 0.0,
    @ColumnInfo(name = "igst_percent") val igstPercent: Double = 0.0,

    /** Total invoice value for this line (taxable + GST). */
    val invoiceValue: Double = 0.0,

    /** Taxable portion of this line — pre-GST. */
    val taxableValue: Double = 0.0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false
)
