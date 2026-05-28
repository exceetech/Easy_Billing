package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CreditNoteItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CreditNoteItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CreditNoteItem>)

    @Query("SELECT * FROM credit_note_items WHERE noteId = :noteId")
    suspend fun getByNote(noteId: Int): List<CreditNoteItem>

    @Query("SELECT * FROM credit_note_items WHERE productId = :productId")
    suspend fun getByProduct(productId: Int): List<CreditNoteItem>

    /**
     * Total returned quantity for a product across all credit notes
     * referencing a particular original bill. Used for partial-return
     * guard logic.
     */
    @Query("""
        SELECT COALESCE(SUM(quantityReturned), 0.0)
        FROM credit_note_items
        WHERE noteId IN (
            SELECT id FROM credit_notes WHERE originalInvoiceId = :billId AND noteType = 'C'
        )
        AND productId = :productId
    """)
    suspend fun getTotalReturnedForBillProduct(billId: Int, productId: Int): Double

    /**
     * Total debited quantity for a product across all debit notes
     * referencing a particular original bill. Used for cancellation logic.
     */
    @Query("""
        SELECT COALESCE(SUM(quantityReturned), 0.0)
        FROM credit_note_items
        WHERE noteId IN (
            SELECT id FROM credit_notes WHERE originalInvoiceId = :billId AND noteType = 'D'
        )
        AND productId = :productId
    """)
    suspend fun getTotalDebitedForBillProduct(billId: Int, productId: Int): Double
}
