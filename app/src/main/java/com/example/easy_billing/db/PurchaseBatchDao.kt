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

    @Query("DELETE FROM purchase_batches WHERE productId = :productId AND quantityRemaining <= 0 AND is_synced = 1")
    suspend fun clearEmptyBatches(productId: Int): Int

    @Query("DELETE FROM purchase_batches WHERE quantityRemaining <= 0 AND is_synced = 1")
    suspend fun clearEmptyBatchesGlobal(): Int

    @Query("DELETE FROM purchase_batches WHERE productId = :productId")
    suspend fun clearAllBatchesForProduct(productId: Int): Int

    @Query("DELETE FROM purchase_batches WHERE batchCode = 'DRIFT-CORRECTION'")
    suspend fun purgeDriftCorrectionBatches(): Int

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

    // getGrossValuationByProduct() — REMOVED (avg-cost audit, Fix 1).
    // This computed a GST-inclusive average cost by independently summing
    // the purchase_batches ledger, completely separately from
    // inventory.averageCost. It was the exact root cause of a past bug
    // where InventoryActivity and Dashboard could show two different
    // average-cost numbers for the same product — fixed by switching
    // InventoryActivity to derive its gross figure from the single
    // canonical inventory.averageCost instead (see InventoryActivity.kt,
    // loadInventory()). This function had no remaining callers (confirmed
    // by a full-codebase grep before removal) — it was dead code left
    // behind after that fix, still capable of silently reintroducing the
    // same divergence if anything ever called it again. There is now
    // exactly ONE place average cost is calculated for display: the
    // inventory.averageCost column, grossed up at display time only.
    // If a batch-ledger-derived valuation is ever genuinely needed again
    // (e.g. for a GSTR report), write it as a clearly-named, separate
    // function that does NOT feed InventoryItemUI.avgCost or any other
    // average-cost display — do not resurrect this one.

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

// ProductGrossValuation data class — REMOVED alongside getGrossValuationByProduct()
// above (avg-cost audit, Fix 1). Was the return type of that now-removed,
// unused, dead-code query — no longer needed.
