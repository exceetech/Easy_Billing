package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for the GST-aware invoice header.
 *
 * Insert returns the auto-generated PK (Long) so the caller can
 * propagate it to every [GstSalesInvoiceItem] before they're
 * written. Reads are kept narrow on purpose — list-style endpoints
 * filter by `shop_id`, by-id reads return a single row, and the
 * sync helpers operate on the [syncStatus] column.
 */
@Dao
interface GstSalesInvoiceDao {

    @Insert
    suspend fun insert(invoice: GstSalesInvoice): Long

    @Query("SELECT * FROM gst_sales_invoice_table WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): GstSalesInvoice?

    @Query("SELECT * FROM gst_sales_invoice_table WHERE bill_id = :billId LIMIT 1")
    suspend fun getByBillId(billId: Int): GstSalesInvoice?

    @Query("SELECT * FROM gst_sales_invoice_table WHERE shop_id = :shopId ORDER BY created_at DESC")
    suspend fun getAllForShop(shopId: String): List<GstSalesInvoice>

    @Query("SELECT * FROM gst_sales_invoice_table ORDER BY created_at DESC")
    suspend fun getAll(): List<GstSalesInvoice>

    // ===== Sync =====

    // NOTE: '!= synced' (not '= pending') so rows previously marked 'failed'
    // by a transient error are picked up and retried on the next pass.
    // The push endpoint is idempotent on (shop_id, local_id), so re-sending
    // a row that did land server-side is harmless.
    @Query("SELECT * FROM gst_sales_invoice_table WHERE sync_status != 'synced' ORDER BY created_at ASC")
    suspend fun getUnsynced(): List<GstSalesInvoice>

    @Query("SELECT COUNT(*) FROM gst_sales_invoice_table WHERE sync_status = 'pending'")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM gst_sales_invoice_table WHERE sync_status = 'failed'")
    suspend fun countFailed(): Int

    @Query("UPDATE gst_sales_invoice_table SET sync_status = 'synced', server_id = :serverId WHERE id = :id")
    suspend fun markSynced(id: Int, serverId: Int?)

    @Query("UPDATE gst_sales_invoice_table SET sync_status = 'failed' WHERE id = :id")
    suspend fun markFailed(id: Int)

    // ===== Cancellation (v23) =====

    @Query("""
        UPDATE gst_sales_invoice_table
        SET is_cancelled = 1, cancelled_at = :cancelledAt, sync_status = 'pending'
        WHERE id = :id
    """)
    suspend fun markCancelled(id: Int, cancelledAt: Long)

    @Query("SELECT is_cancelled FROM gst_sales_invoice_table WHERE id = :id LIMIT 1")
    suspend fun isCancelled(id: Int): Boolean?

    @Query("UPDATE gst_sales_invoice_table SET invoice_number = :invoiceNumber WHERE bill_id = :billId")
    suspend fun updateInvoiceNumberByBillId(billId: Int, invoiceNumber: String)
}
