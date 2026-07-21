package com.example.easy_billing.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ImportServiceDao {

    @Insert
    suspend fun insert(importService: ImportService): Long

    @Insert
    suspend fun insertAll(importServices: List<ImportService>)

    @Update
    suspend fun update(importService: ImportService)

    @Query("SELECT * FROM import_services ORDER BY invoice_date DESC")
    fun getAllImportServicesLive(): LiveData<List<ImportService>>

    @Query("SELECT * FROM import_services ORDER BY invoice_date DESC")
    suspend fun getAllImportServices(): List<ImportService>

    @Query("SELECT * FROM import_services WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): ImportService?

    /**
     * Any other record already carrying this invoice number.
     *
     * Nothing enforces that an import invoice number is unique — not locally,
     * not on the server — so the same invoice can be entered twice and its ITC
     * claimed twice. Used to warn before saving rather than to block: a hard
     * rule would be wrong, since two foreign suppliers can legitimately issue
     * the same number.
     *
     * [excludeId] keeps a record from matching itself when being edited.
     */
    @Query("""
        SELECT * FROM import_services
        WHERE invoice_number = :invoiceNumber COLLATE NOCASE
          AND id != :excludeId
        ORDER BY id ASC LIMIT 1
    """)
    suspend fun findByInvoiceNumber(invoiceNumber: String, excludeId: Int): ImportService?

    @Query("SELECT * FROM import_services WHERE sync_status = 'pending'")
    suspend fun getPendingSyncImportServices(): List<ImportService>

    @Query("SELECT COUNT(*) FROM import_services WHERE sync_status = 'pending'")
    suspend fun countPending(): Int

    @Query("UPDATE import_services SET sync_status = 'synced' WHERE id = :id")
    suspend fun markAsSynced(id: Int)

    /**
     * Parks a record the server refused for breaking a GSTR-2 rule.
     *
     * Only 'pending' rows are picked up for sync, so moving it out of that
     * state stops it being retried on every sync forever — which is what a
     * record saved before those rules existed would otherwise do, blocking
     * the valid records queued behind it.
     */
    @Query("UPDATE import_services SET sync_status = 'rejected' WHERE id = :id")
    suspend fun markAsRejected(id: Int)

    @Query("SELECT COUNT(*) FROM import_services WHERE sync_status = 'rejected'")
    suspend fun countRejected(): Int

    /**
     * Deletes one record. Only offered in the UI for rows that never reached
     * the server (pending / rejected) — there is no delete endpoint, so
     * removing a synced row locally would leave an orphan on the server that
     * the next pull would simply bring back.
     */
    @Query("DELETE FROM import_services WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM import_services")
    suspend fun deleteAll()
}
