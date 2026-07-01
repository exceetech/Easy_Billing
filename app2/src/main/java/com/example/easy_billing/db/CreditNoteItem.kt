package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single line in a Sales Return Credit Note (`credit_note_items`).
 *
 * One [CreditNoteItem] corresponds to one product being returned.
 * All financial values are derived proportionally from the original
 * [BillItem] using the formula:
 *
 *   returnedTaxableValue = (quantityReturned / quantitySold) * originalTaxableValue
 *
 * The [costPriceUsed] is taken directly from [BillItem.costPriceUsed]
 * so that inventory restoration records the historically correct cost.
 */
@Entity(
    tableName = "credit_note_items",
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["productId"])
    ]
)
data class CreditNoteItem(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** FK → credit_notes.id */
    val noteId: Int,

    /** Local product id from `products` table. */
    val productId: Int,
    val productName: String,
    val variant: String? = null,
    val hsnCode: String = "",
    val unit: String = "",

    // ── Quantities ──────────────────────────────────────────────────
    val quantitySold: Double,
    val quantityReturned: Double,

    // ── Pricing ─────────────────────────────────────────────────────
    /** Selling price per unit (from original BillItem.price). */
    val rate: Double,

    /**
     * Historical cost price at the moment of the original sale.
     * Sourced from [BillItem.costPriceUsed]. Used to restore
     * inventory at the correct FIFO cost.
     */
    val costPriceUsed: Double = 0.0,

    // ── Returned financials (proportional to quantityReturned) ──────
    val taxableValue: Double,

    @ColumnInfo(name = "gst_rate") val gstRate: Double = 0.0,
    @ColumnInfo(name = "cgst_amount") val cgstAmount: Double = 0.0,
    @ColumnInfo(name = "sgst_amount") val sgstAmount: Double = 0.0,
    @ColumnInfo(name = "igst_amount") val igstAmount: Double = 0.0,
    val cessAmount: Double = 0.0,
    val taxAmount: Double = 0.0,
    val totalAmount: Double = 0.0,

    /**
     * FK reference back to the original `bill_items.id` row.
     * Enables precise traceability without denormalising the original
     * data. Nullable for edge cases where bill items were not found.
     */
    val originalBillItemId: Int? = null
)
