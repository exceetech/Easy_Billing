package com.example.easy_billing

import androidx.room.withTransaction
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.PurchaseBatch

/**
 * Pure-Kotlin utility behind the hybrid inventory model.
 *
 *   • FIFO consumption        — [consumeFifo]
 *   • Per-batch reductions    — [reduceBatches] (supplier returns)
 *   • Average cost derivation — [recomputeAvgFromBatches]
 *   • Migration back-fill     — [ensureSyntheticBatch]
 *   • Real batch insert       — [recordBatch]
 *
 * Every public function is idempotent at the inventory-row level: it
 * touches `inventory.averageCost` only when the derived value
 * differs, so no spurious sync churn is generated.
 *
 * IMPORTANT: This object **never** changes `inventory.currentStock`
 * — that stays under [InventoryManager]'s control so the existing
 * sales / scrap / clear-stock paths keep their behavioural contracts
 * intact. We only ever realign `averageCost` to RULE 3:
 *
 *     avg = SUM(remainingQty × unitCostExcludingTax) / SUM(remainingQty)
 *
 * If nothing remains, the avg drops to zero (RULE 4).
 */
object InventoryValuation {

    /**
     * FIFO consumer used by sales / scrap / expired / manual adjust.
     *
     * Walks the remaining batches oldest first and debits up to
     * [quantity] across them. If a product has stock but no batches
     * (legacy / migration scenario where the v20 → v21 back-fill did
     * not seed one, e.g. a manual stock add made before this utility
     * existed), [ensureSyntheticBatch] seeds one on the fly so the
     * ledger never silently drifts.
     *
     * Returns the qty actually consumed — usually equals [quantity].
     * A short consume means the batch pool was depleted faster than
     * the inventory row, which would indicate prior drift that the
     * caller should already have rejected via the `currentStock`
     * guard upstream.
     */
    suspend fun consumeFifo(
        db: AppDatabase,
        productId: Int,
        quantity: Double
    ): Double {
        if (quantity <= 0.0) return 0.0
        require(productId > 0) { "Invalid productId" }

        return db.withTransaction {
            val batches = db.purchaseBatchDao().getRemainingBatches(productId)
            var remaining = quantity
            var consumed = 0.0

            for (b in batches) {
                if (remaining <= 0.0) break
                if (b.quantityRemaining <= 0.0) continue

                val take = minOf(b.quantityRemaining, remaining)
                db.purchaseBatchDao().updateBatch(
                    b.copy(
                        quantityRemaining = b.quantityRemaining - take,
                        isSynced = false
                    )
                )

                remaining -= take
                consumed += take
            }

            // Drop zero-qty batches so future FIFO walks stay tight.
            db.purchaseBatchDao().clearEmptyBatches(productId)

            // Recompute average cost from whatever's left.
            recomputeAvgFromBatches(db, productId)

            consumed
        }
    }

    /**
     * Per-batch debit used by the Supplier Return flow.
     *
     * The user has explicitly chosen which invoice batches to send
     * back to the supplier and the quantity per batch — the spec
     * mandates we value those at the batch's own unit cost, not the
     * weighted average. This skips the FIFO walker entirely.
     *
     * The accompanying [InventoryManager.reduceStock] call (with
     * `skipBatchConsume = true`) handles the inventory row, log, and
     * transaction history.
     *
     * Returns the total quantity reduced across all lines.
     */
    suspend fun reduceBatches(
        db: AppDatabase,
        productId: Int,
        lines: List<BatchReduction>
    ): Double {
        require(productId > 0) { "Invalid productId" }
        if (lines.isEmpty()) return 0.0

        return db.withTransaction {
            var totalReduced = 0.0
            for (line in lines) {
                require(line.quantity > 0.0) { "Batch reduction qty must be > 0" }

                val updated = db.purchaseBatchDao().reduceBatchQuantity(
                    batchId = line.batchId,
                    qty = line.quantity
                )
                if (updated == 0) {
                    // The conditional UPDATE refused — either the
                    // batch is gone, belongs to a different product,
                    // or doesn't have enough qty left. Surface the
                    // most informative message we can.
                    val b = db.purchaseBatchDao().getBatchById(line.batchId)
                    if (b == null) {
                        throw IllegalStateException("Batch ${line.batchId} not found")
                    }
                    throw IllegalStateException(
                        "Batch ${b.id} only has ${b.quantityRemaining} remaining, " +
                            "requested ${line.quantity}"
                    )
                }
                totalReduced += line.quantity
            }

            db.purchaseBatchDao().clearEmptyBatches(productId)
            recomputeAvgFromBatches(db, productId)

            totalReduced
        }
    }

    /**
     * Realigns `inventory.averageCost` to RULE 3.
     *
     * Never writes if the derived value already matches what's on the
     * row — that keeps `isSynced` from flipping on no-op recomputes.
     */
    suspend fun recomputeAvgFromBatches(db: AppDatabase, productId: Int) {
        val totals = db.purchaseBatchDao().getValuationTotals(productId)
        val inventory = db.inventoryDao().getInventory(productId) ?: return

        val newAvg = if (totals.totalQty > 0.0) totals.totalValue / totals.totalQty else 0.0

        // Float comparison tolerance — anything within a paisa is
        // already aligned, no need to dirty the row.
        if (kotlin.math.abs(newAvg - inventory.averageCost) < 0.0001) return

        db.inventoryDao().update(
            inventory.copy(averageCost = newAvg, isSynced = false)
        )
    }

    /**
     * Migration helper. If a product currently has stock but no
     * surviving batches (legacy row, or a stock add that bypassed
     * [recordBatch]), seed one synthetic batch carrying the current
     * average cost so the ledger covers the existing units.
     *
     * `createdAt = 0` ensures FIFO drains it first — fresh purchases
     * will append batches at a later timestamp and live on top of it.
     */
    suspend fun ensureSyntheticBatch(db: AppDatabase, productId: Int) {
        val totals = db.purchaseBatchDao().getValuationTotals(productId)
        val inventory = db.inventoryDao().getInventory(productId) ?: return

        // If there are batches but their sum differs from inventory.currentStock,
        // or if there are no batches at all but we have positive stock on hand:
        val hasDrift = totals.totalQty > 0.0 && kotlin.math.abs(totals.totalQty - inventory.currentStock) > 0.001

        if (totals.totalQty <= 0.0 || hasDrift) {
            if (inventory.currentStock <= 0.0) {
                // Clear any orphaned empty or positive batches if stock is zero
                db.purchaseBatchDao().clearAllBatchesForProduct(productId)
                return
            }

            val product = db.productDao().getById(productId)
            db.purchaseBatchDao().clearAllBatchesForProduct(productId)

            db.purchaseBatchDao().insertBatch(
                PurchaseBatch(
                    productId = productId,
                    purchaseInvoiceId = null,
                    supplierName = null,
                    supplierGstin = null,
                    invoiceNumber = null,
                    batchCode = "MIGRATION",
                    quantityPurchased = inventory.currentStock,
                    quantityRemaining = inventory.currentStock,
                    unitCostExcludingTax = inventory.averageCost,
                    gstPercent = product?.defaultGstRate ?: 0.0,
                    cgstPercent = product?.cgstPercentage ?: 0.0,
                    sgstPercent = product?.sgstPercentage ?: 0.0,
                    igstPercent = product?.igstPercentage ?: 0.0,
                    invoiceValue = inventory.currentStock * inventory.averageCost,
                    taxableValue = inventory.currentStock * inventory.averageCost,
                    createdAt = 0L,
                    isSynced = true   // synthetic — never push to backend
                )
            )
        }
    }

    /**
     * Records a fresh purchase batch and immediately refreshes the
     * weighted average. Used by [InventoryManager.addStock] and
     * [com.example.easy_billing.repository.PurchaseRepository] so the
     * inventory row's `averageCost` always equals what the batch
     * ledger says it should.
     *
     * Returns the new batch id.
     */
    suspend fun recordBatch(db: AppDatabase, batch: PurchaseBatch): Long {
        require(batch.productId > 0) { "Invalid productId" }
        require(batch.quantityPurchased > 0.0) { "Invalid quantityPurchased" }

        return db.withTransaction {
            // Force quantityRemaining == quantityPurchased on insert
            // — callers should not be able to seed a half-spent batch
            // through this entrypoint.
            val id = db.purchaseBatchDao().insertBatch(
                batch.copy(quantityRemaining = batch.quantityPurchased)
            )
            recomputeAvgFromBatches(db, batch.productId)
            id
        }
    }

    /**
     * Diagnostic helper — does the batch ledger sum match the
     * `inventory.currentStock`? Use sparingly (tests, debug menus).
     */
    suspend fun isConsistent(db: AppDatabase, productId: Int): Boolean {
        val totals = db.purchaseBatchDao().getValuationTotals(productId)
        val inv = db.inventoryDao().getInventory(productId) ?: return totals.totalQty == 0.0
        return kotlin.math.abs(totals.totalQty - inv.currentStock) < 0.0001
    }

    /** Per-batch reduction line — used by supplier-return flows. */
    data class BatchReduction(
        val batchId: Int,
        val quantity: Double
    )
}
