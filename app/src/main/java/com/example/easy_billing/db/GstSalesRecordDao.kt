package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GstSalesRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<GstSalesRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: GstSalesRecord)

    @Query("SELECT * FROM gst_sales_records ORDER BY invoiceDate DESC")
    suspend fun getAll(): List<GstSalesRecord>

    // ===== Sync =====
    @Query("SELECT * FROM gst_sales_records WHERE sync_status = 'pending' ORDER BY createdAt ASC")
    suspend fun getUnsynced(): List<GstSalesRecord>

    @Query("UPDATE gst_sales_records SET sync_status = 'synced' WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<String>)

    @Query("UPDATE gst_sales_records SET sync_status = 'failed' WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    // ===== Reports — by date range (epoch millis) =====
    @Query("SELECT * FROM gst_sales_records WHERE invoiceDate >= :start AND invoiceDate <= :end ORDER BY invoiceDate ASC")
    suspend fun getByDateRange(start: Long, end: Long): List<GstSalesRecord>

    @Query("SELECT * FROM gst_sales_records WHERE customerType = 'B2B' AND invoiceDate >= :start AND invoiceDate <= :end")
    suspend fun getGstr1B2B(start: Long, end: Long): List<GstSalesRecord>

    @Query("SELECT * FROM gst_sales_records WHERE customerType = 'B2C' AND invoiceDate >= :start AND invoiceDate <= :end")
    suspend fun getGstr1B2C(start: Long, end: Long): List<GstSalesRecord>

    // ===== HSN Summary =====
    @Query("""
        SELECT hsnCode, unit,
               SUM(quantity) AS totalQty,
               SUM(taxableValue) AS totalTaxable,
               SUM(cgstAmount) AS totalCgst,
               SUM(sgstAmount) AS totalSgst,
               SUM(igstAmount) AS totalIgst
        FROM gst_sales_records
        WHERE invoiceDate >= :start AND invoiceDate <= :end
        GROUP BY hsnCode, unit
        ORDER BY hsnCode ASC
    """)
    suspend fun getHsnSummary(start: Long, end: Long): List<HsnSummaryRow>

    // ===== GSTR-3B Outward Totals =====
    @Query("""
        SELECT SUM(taxableValue) as taxable,
               SUM(cgstAmount) as cgst,
               SUM(sgstAmount) as sgst,
               SUM(igstAmount) as igst
        FROM gst_sales_records
        WHERE invoiceDate >= :start AND invoiceDate <= :end
    """)
    suspend fun getGstr3BTotals(start: Long, end: Long): GstTotalsRow?
}

/** Lightweight projection for HSN summary aggregation. */
data class HsnSummaryRow(
    val hsnCode: String,
    val unit: String,
    val totalQty: Double,
    val totalTaxable: Double,
    val totalCgst: Double,
    val totalSgst: Double,
    val totalIgst: Double
)

data class GstTotalsRow(
    val taxable: Double?,
    val cgst: Double?,
    val sgst: Double?,
    val igst: Double?
)
