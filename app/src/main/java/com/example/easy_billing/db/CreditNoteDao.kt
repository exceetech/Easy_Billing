package com.example.easy_billing.db

import com.example.easy_billing.util.appNow

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface CreditNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: CreditNote): Long

    @Update
    suspend fun update(note: CreditNote)

    @Query("SELECT * FROM credit_notes WHERE id = :id")
    suspend fun getById(id: Int): CreditNote?

    @Query("SELECT * FROM credit_notes ORDER BY created_at DESC")
    suspend fun getAll(): List<CreditNote>

    @Query("SELECT * FROM credit_notes WHERE originalInvoiceId = :billId ORDER BY created_at DESC")
    suspend fun getByOriginalInvoice(billId: Int): List<CreditNote>

    @Query("SELECT * FROM credit_notes WHERE syncStatus != 'synced' ORDER BY created_at ASC")
    suspend fun getUnsynced(): List<CreditNote>

    @Query("SELECT COUNT(*) FROM credit_notes WHERE syncStatus = 'pending'")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM credit_notes WHERE syncStatus = 'failed'")
    suspend fun countFailed(): Int

    @Query("UPDATE credit_notes SET syncStatus = 'synced', updated_at = :updatedAt WHERE id = :id")
    suspend fun markSynced(id: Int, updatedAt: Long = appNow())

    @Query("UPDATE credit_notes SET syncStatus = 'failed', updated_at = :updatedAt WHERE id = :id")
    suspend fun markFailed(id: Int, updatedAt: Long = appNow())

    /**
     * Returns the highest existing note sequence number.
     * The noteNumber format is "CN-XXXXX" — we extract the integer
     * suffix with CAST(SUBSTR(noteNumber, 4) AS INTEGER).
     * Returns 0 if no credit note exists yet.
     */
    @Query("""
        SELECT COALESCE(MAX(CAST(SUBSTR(noteNumber, 4) AS INTEGER)), 0)
        FROM credit_notes
    """)
    suspend fun getMaxSequence(): Int

    /**
     * Returns the total quantity already returned for a specific
     * product on a specific original bill. Used to enforce the
     * "totalReturned ≤ quantitySold" constraint before saving.
     */
    @Query("""
        SELECT COALESCE(SUM(cni.quantityReturned), 0.0)
        FROM credit_note_items cni
        INNER JOIN credit_notes cn ON cni.noteId = cn.id
        WHERE cn.originalInvoiceId = :billId
          AND cn.noteType = 'C'
          AND cni.productId = :productId
    """)
    suspend fun getTotalReturnedQty(billId: Int, productId: Int): Double
}
