package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PurchaseReturnDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PurchaseReturn): Long

    @Query("SELECT * FROM purchase_return_table ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<PurchaseReturn>

    @Query("SELECT * FROM purchase_return_table WHERE productId = :productId")
    suspend fun getByProduct(productId: Int): List<PurchaseReturn>

    @Query("SELECT * FROM purchase_return_table WHERE is_synced = 0")
    suspend fun getUnsynced(): List<PurchaseReturn>

    @Query("SELECT COUNT(*) FROM purchase_return_table WHERE is_synced = 0")
    suspend fun countUnsynced(): Int

    @Query("UPDATE purchase_return_table SET is_synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Int)

    // ── Debit Note support (v25) ─────────────────────────────────────

    /** All returns that originated from a specific purchase invoice. */
    @Query("SELECT * FROM purchase_return_table WHERE original_invoice_id = :purchaseId ORDER BY created_at DESC")
    suspend fun getByOriginalInvoice(purchaseId: Int): List<PurchaseReturn>

    /**
     * Total quantity already returned for a specific product on a
     * specific purchase invoice. Used to enforce the constraint that
     * totalReturned ≤ quantityPurchased before saving a debit note.
     */
    @Query("""
        SELECT COALESCE(SUM(
            CASE WHEN note_type = 'D' THEN quantityReturned
                 WHEN note_type = 'C' THEN -quantityReturned
                 ELSE 0.0 
            END
        ), 0.0)
        FROM purchase_return_table
        WHERE original_invoice_id = :purchaseId AND productId = :productId
          AND (note_kind IS NULL OR note_kind != 'PRICE_CORRECTION')
    """)
    suspend fun getTotalReturnedForInvoiceProduct(purchaseId: Int, productId: Int): Double

    // ── Purchase correction notes (QDN / PDN), Phase 2 ─────────────────
    //
    // Held pens H for one bill line = quantity billed − getNetQtyOut(...).
    // "Net out" is every note that took pens off the bill (RETURN and
    // QTY_CORRECTION as D) minus every note that put pens on it (ADDITION
    // and QTY_CORRECTION as C). Price notes (PRICE_CORRECTION) carry the
    // pens they cover in `quantityReturned` but move none, so they are
    // never counted. This is the same figure as
    // [getTotalReturnedForInvoiceProduct]; the extension [getNetQtyOut]
    // below gives it the name correction code reads best (see [heldQuantity]).

    /**
     * Sum of per-pen NET rate changes made by price notes on this bill
     * line (each row's `rate_delta_net` is already signed new − old).
     * Corrected net rate n_cur = original net rate + this.
     */
    @Query("""
        SELECT COALESCE(SUM(rate_delta_net), 0.0)
        FROM purchase_return_table
        WHERE original_invoice_id = :purchaseId AND productId = :productId
          AND note_kind = 'PRICE_CORRECTION'
    """)
    suspend fun getNetRateDeltaForLine(purchaseId: Int, productId: Int): Double

    /**
     * Sum of per-pen GROSS (GST-inclusive) rate changes made by price notes
     * on this bill line. A price note stores its value unsigned plus its
     * direction, so each row contributes ±value ÷ pens covered (D lowers,
     * C raises). Corrected gross rate g_cur = original gross rate + this.
     */
    @Query("""
        SELECT COALESCE(SUM(
            (CASE WHEN note_type = 'C' THEN 1.0 ELSE -1.0 END)
            * note_refund_voucher_value / quantityReturned
        ), 0.0)
        FROM purchase_return_table
        WHERE original_invoice_id = :purchaseId AND productId = :productId
          AND note_kind = 'PRICE_CORRECTION' AND quantityReturned > 0
    """)
    suspend fun getGrossRateDeltaForLine(purchaseId: Int, productId: Int): Double

    /** Every note on one bill line, oldest first (for the prior-notes list). */
    @Query("""
        SELECT * FROM purchase_return_table
        WHERE original_invoice_id = :purchaseId AND productId = :productId
        ORDER BY created_at ASC, id ASC
    """)
    suspend fun getNotesForLine(purchaseId: Int, productId: Int): List<PurchaseReturn>

    /**
     * Stage-1 fix P0-5: net value of correction notes for the Profit
     * "Expense" tile. D (return) = money back, so negative; C (addition)
     * = extra spend, so positive. Gross (GST-inclusive) like invoiceValue.
     * Notes on a CANCELLED purchase are left out: that purchase is already
     * excluded from Expense, so counting its notes would double-subtract.
     */
    @Query("""
        SELECT COALESCE(SUM(
            CASE WHEN note_type = 'C' THEN note_refund_voucher_value
                 ELSE -note_refund_voucher_value
            END
        ), 0.0)
        FROM purchase_return_table
        WHERE created_at BETWEEN :start AND :end
          AND (original_invoice_id IS NULL
               OR original_invoice_id NOT IN (SELECT id FROM purchase_table WHERE is_cancelled = 1))
    """)
    suspend fun getNetNoteValueBetween(start: Long, end: Long): Double

    @Query("""
        SELECT COALESCE(SUM(
            CASE WHEN note_type = 'C' THEN note_refund_voucher_value
                 ELSE -note_refund_voucher_value
            END
        ), 0.0)
        FROM purchase_return_table
        WHERE original_invoice_id IS NULL
           OR original_invoice_id NOT IN (SELECT id FROM purchase_table WHERE is_cancelled = 1)
    """)
    suspend fun getNetNoteValueAll(): Double

    /**
     * Returns the highest debit note sequence number.
     * The noteNumber format is "DN-XXXXX" — we extract the integer
     * suffix with CAST(SUBSTR(note_number, 4) AS INTEGER).
     * Returns 0 if no debit note exists yet.
     */
    @Query("""
        SELECT COALESCE(MAX(CAST(SUBSTR(note_number, 4) AS INTEGER)), 0)
        FROM purchase_return_table
        WHERE note_number IS NOT NULL AND note_number LIKE 'DN-%'
    """)
    suspend fun getMaxDebitNoteSequence(): Int

    /**
     * Returns the highest credit note sequence number.
     * The noteNumber format is "CN-XXXXX" — we extract the integer
     * suffix with CAST(SUBSTR(note_number, 4) AS INTEGER).
     * Returns 0 if no credit note exists yet.
     */
    @Query("""
        SELECT COALESCE(MAX(CAST(SUBSTR(note_number, 4) AS INTEGER)), 0)
        FROM purchase_return_table
        WHERE note_number IS NOT NULL AND note_number LIKE 'CN-%'
    """)
    suspend fun getMaxCreditNoteSequence(): Int
}

/**
 * Pens still held on a bill line: billed − net taken off by notes.
 * Never negative (a guard against inconsistent legacy data).
 */
fun heldQuantity(billedQuantity: Double, netQtyOut: Double): Double =
    (billedQuantity - netQtyOut).coerceAtLeast(0.0)

/** Net pens taken off this bill line by notes (D − C), price notes excluded. */
suspend fun PurchaseReturnDao.getNetQtyOut(purchaseId: Int, productId: Int): Double =
    getTotalReturnedForInvoiceProduct(purchaseId, productId)
