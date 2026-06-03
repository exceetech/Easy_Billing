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
    """)
    suspend fun getTotalReturnedForInvoiceProduct(purchaseId: Int, productId: Int): Double

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
