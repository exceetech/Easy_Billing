package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * DAO for `purchase_batches`.
 *
 * All consumption helpers preserve the invariants:
 *   • quantityRemaining never goes negative
 *   • inserts are reversible via [updateBatch]
 *   • [getValuationTotals] only counts batches with stock left, so the
 *     weighted-average derivation matches RULE 3 of the spec.
 */
@Dao
interface PurchaseBatchDao {

    /* ─── Writes ─── */

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(batch: PurchaseBatch): Long

    @Update
    suspend fun updateBatch(batch: PurchaseBatch)

    /**
     * Atomic batch debit. Returns the number of rows actually updated
     * — `0` means the batch did not have enough stock, so the caller
     * must abort the transaction.
     */
    @Query(
        """
        UPDATE purchase_batches
        SET quantityRemaining = quantityRemaining - :qty,
            is_synced = 0
        WHERE id = :batchId AND quantityRemaining >= :qty
        """
    )
    suspend fun reduceBatchQuantity(batchId: Int, qty: Double): Int

    /** Removes batches with 0 (or negative, defensively) qty left. */
    @Query(
        """
        DELETE FROM purchase_batches
        WHERE productId = :productId AND quantityRemaining <= 0
        """
    )
    suspend fun clearEmptyBatches(productId: Int): Int

    @Query("DELETE FROM purchase_batches WHERE productId = :productId")
    suspend fun clearAllBatchesForProduct(productId: Int): Int

    /* ─── Reads ─── */

    /** Oldest-first list of batches that still have stock — FIFO. */
    @Query(
        """
        SELECT * FROM purchase_batches
        WHERE productId = :productId AND quantityRemaining > 0
        ORDER BY created_at ASC, id ASC
        """
    )
    suspend fun getRemainingBatches(productId: Int): List<PurchaseBatch>

    /** Every batch ever recorded for a product (debug / audit). */
    @Query(
        """
        SELECT * FROM purchase_batches
        WHERE productId = :productId
        ORDER BY created_at ASC, id ASC
        """
    )
    suspend fun getAllBatches(productId: Int): List<PurchaseBatch>

    @Query("SELECT * FROM purchase_batches WHERE id = :id LIMIT 1")
    suspend fun getBatchById(id: Int): PurchaseBatch?

    /**
     * Aggregate snapshot used by [com.example.easy_billing.InventoryValuation].
     * Both numbers are scoped to batches with stock > 0 only so the
     * weighted-average matches the spec (RULE 3).
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(quantityRemaining), 0.0)                              AS totalQty,
            COALESCE(SUM(quantityRemaining * unit_cost_excluding_tax), 0.0)    AS totalValue
        FROM purchase_batches
        WHERE productId = :productId AND quantityRemaining > 0
        """
    )
    suspend fun getValuationTotals(productId: Int): ValuationTotals

    /**
     * Per-product GROSS stock valuation — values remaining stock at the invoice
     * value (incl. GST) rather than the net taxable cost. Used ONLY for the
     * inventory "stock value" display; COGS/profit/returns keep using the net
     * unit_cost_excluding_tax. Gross unit = invoiceValue / quantityPurchased.
     */
    @Query(
        """
        SELECT productId AS productId,
               COALESCE(SUM(quantityRemaining), 0.0) AS totalQty,
               COALESCE(SUM(quantityRemaining * (invoiceValue / quantityPurchased)), 0.0) AS totalValue
        FROM purchase_batches
        WHERE quantityRemaining > 0 AND quantityPurchased > 0
        GROUP BY productId
        """
    )
    suspend fun getGrossValuationByProduct(): List<ProductGrossValuation>

    /* ─── Sync helpers ─── */

    @Query("SELECT * FROM purchase_batches WHERE is_synced = 0")
    suspend fun getUnsynced(): List<PurchaseBatch>

    /** All batches across all products — used to build the pull dedupe index (H2). */
    @Query("SELECT * FROM purchase_batches")
    suspend fun getAllBatchesGlobal(): List<PurchaseBatch>

    @Query("UPDATE purchase_batches SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Int>)
}

/**
 * Aggregate result for [PurchaseBatchDao.getValuationTotals]. Kept as
 * a plain data class so Room can auto-map columns by name.
 */
data class ValuationTotals(
    val totalQty: Double,
    val totalValue: Double
) {
    /** Convenience — RULE 3 weighted-average. */
    val averageCost: Double
        get() = if (totalQty > 0.0) totalValue / totalQty else 0.0
}

/**
 * Per-product GROSS valuation row for [PurchaseBatchDao.getGrossValuationByProduct].
 */
data class ProductGrossValuation(
    val productId: Int,
    val totalQty: Double,
    val totalValue: Double
) {
    /** GST-inclusive weighted-average unit cost. */
    val grossAvgCost: Double
        get() = if (totalQty > 0.0) totalValue / totalQty else 0.0
}
