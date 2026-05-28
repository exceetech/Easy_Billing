package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Sales Return Credit Note (`credit_notes`).
 *
 * Issued when goods are returned by a customer against a prior sales
 * invoice. One credit note may cover a partial or full return of one
 * or more items from the original bill.
 *
 * GST fields mirror the structure required for GSTR-1 CDN
 * (Credit / Debit Note) reporting.
 */
@Entity(
    tableName = "credit_notes",
    indices = [
        Index(value = ["noteNumber"], unique = true),
        Index(value = ["originalInvoiceId"]),
        Index(value = ["syncStatus"])
    ]
)
data class CreditNote(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** Auto-generated sequential number, e.g. "CN-00001". */
    val noteNumber: String,

    /** Epoch millis when this credit note was created. */
    val noteDate: Long = System.currentTimeMillis(),

    /** Always "C" for Credit Note or "D" for Debit Note. */
    val noteType: String = "C",

    /** "Regular", "SEZ supplies with payment", "SEZ supplies without payment", "Deemed Exp" */
    val noteSupplyType: String = "Regular",

    // ── Original invoice reference ──────────────────────────────────
    /** Local Room `bills.id` of the original sales invoice. */
    val originalInvoiceId: Int,

    /** Bill number printed on the invoice, e.g. "INV-2026-0042". */
    val originalInvoiceNumber: String,

    /** Epoch millis of the original invoice date. */
    val originalInvoiceDate: Long,

    // ── Customer / GST metadata (copied from original invoice) ──────
    val customerName: String = "",
    val customerGstin: String? = null,
    val placeOfSupply: String = "",
    val reverseCharge: String = "N",
    val supplyType: String = "intrastate",

    /**
     * GST invoice type for GSTR-1 CDN table:
     * "B2B", "B2CL", "B2CS", "EXPORT", etc.
     */
    val urType: String = "B2CS",

    // ── Aggregate financials (sum of all CreditNoteItems) ───────────
    val taxableValue: Double = 0.0,
    val taxAmount: Double = 0.0,
    val cessAmount: Double = 0.0,
    val totalAmount: Double = 0.0,

    // ── GST component breakdowns ─────────────────────────────────────
    @ColumnInfo(name = "cgst_amount") val cgstAmount: Double = 0.0,
    @ColumnInfo(name = "sgst_amount") val sgstAmount: Double = 0.0,
    @ColumnInfo(name = "igst_amount") val igstAmount: Double = 0.0,

    // ── GSTR-1 DOCS Fields ────────────────────────────────────────────
    @ColumnInfo(name = "document_type") val documentType: String = "",
    @ColumnInfo(name = "document_nature") val documentNature: String = "",
    @ColumnInfo(name = "document_series") val documentSeries: String = "",

    // ── Sync ─────────────────────────────────────────────────────────
    /** "pending" | "synced" | "failed" */
    val syncStatus: String = "pending",

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
