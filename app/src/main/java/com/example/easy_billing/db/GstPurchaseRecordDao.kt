package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GstPurchaseRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: GstPurchaseRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<GstPurchaseRecord>)

    @Query("SELECT * FROM gst_purchase_records ORDER BY invoiceDate DESC")
    suspend fun getAll(): List<GstPurchaseRecord>

    // ===== Sync =====
    @Query("SELECT * FROM gst_purchase_records WHERE sync_status = 'pending' ORDER BY createdAt ASC")
    suspend fun getUnsynced(): List<GstPurchaseRecord>

    @Query("UPDATE gst_purchase_records SET sync_status = 'synced' WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<String>)

    @Query("UPDATE gst_purchase_records SET sync_status = 'failed' WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    // ===== Reports =====
    @Query("SELECT * FROM gst_purchase_records WHERE invoiceDate >= :start AND invoiceDate <= :end ORDER BY invoiceDate ASC")
    suspend fun getByDateRange(start: Long, end: Long): List<GstPurchaseRecord>

    // ===== GSTR-3B ITC =====
    @Query("""
        SELECT SUM(taxableValue) as taxable,
               SUM(cgstAmount) as cgst,
               SUM(sgstAmount) as sgst,
               SUM(igstAmount) as igst
        FROM gst_purchase_records
        WHERE invoiceDate >= :start AND invoiceDate <= :end
    """)
    suspend fun getItcTotals(start: Long, end: Long): GstTotalsRow?

    // ===== HSN Summary for Purchases =====
    @Query("""
        SELECT hsnCode AS hsnCode, 'NOS' AS unit,
               SUM(1) AS totalQty,
               SUM(taxableValue) AS totalTaxable,
               SUM(cgstAmount) AS totalCgst,
               SUM(sgstAmount) AS totalSgst,
               SUM(igstAmount) AS totalIgst
        FROM gst_purchase_records
        WHERE invoiceDate >= :start AND invoiceDate <= :end
        GROUP BY hsnCode
        ORDER BY hsnCode ASC
    """)
    suspend fun getHsnSummary(start: Long, end: Long): List<HsnSummaryRow>
}
