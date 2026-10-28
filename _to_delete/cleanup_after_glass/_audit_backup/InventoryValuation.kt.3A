package com.example.easy_billing

import androidx.room.withTransaction
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.PurchaseBatch

/**
 * Pure-Kotlin utility behind the hybrid inventory model.
 *
 *   • FIFO consumption (quantity only) — [consumeFifo]
 *   • Per-batch reductions (quantity only) — [reduceBatches] (supplier returns)
 *   • Migration back-fill        — [ensureSyntheticBatch]
 *   • Real batch insert          — [recordBatch]
 *
 * IMPORTANT: This object **never** changes `inventory.currentStock`
 * — that stays under [InventoryManager]'s control so the existing
 * sales / scrap / clear-stock paths keep their behavioural contracts
 * intact.
 *
 * Moving-average redesign (avg-cost audit, Phase 1): average cost is
 * NO LONGER derived by summing the batch ledger. It used to be —
 * every function below recomputed `inventory.averageCost` from
 * SUM(remainingQty × unitCost) / SUM(remainingQty) every time a batch
 * changed, which meant the displayed average moved on every single
 * sale, scrap, and purchase return, not just purchases. That's what
 * caused the average to visibly jump around for reasons that weren't
 * obvious to whoever was looking at the screen, and — more seriously
 * — meant a chain of sale + purchase-return + customer-return events
 * could silently drift the average away from where it should be, with
 * no single step being individually "wrong."
 *
 * The new rule: average cost only ever changes on two events —
 * a purchase (blended in by [InventoryManager.addStock]'s own
 * weighted-average formula) and a customer sales-return (blended in
 * at the rate that was recorded on the original bill). Every OUTFLOW
 * — a sale, a scrap/loss, or a purchase return — removes stock at
 * whatever the average currently is, which by definition can never
 * change that average. The batch ledger below still tracks exactly
 * how much of each purchase invoice remains (needed to validate
 * purchase-return quantities against a specific invoice), it just no
 * longer feeds back into `inventory.averageCost`.
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

            // Moving-average redesign (Phase 1): no average-cost recompute
            // here anymore. A sale/scrap removes stock at whatever the
            // average already is — mathematically, that can never change
            // the average of what's left, so there's nothing to recompute.
            // The FIFO walk above still exists purely to track remaining
            // quantity per batch/invoice (needed for purchase-return
            // validation), not to derive cost anymore.

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
                    val b = db.purchaseBatchDao().getBatchById(line.batchId)
                    if (b == null) {
                        throw IllegalStateException("Batch ${line.batchId} not found")
                    }
                    if (b.quantityRemaining > 0.0) {
                        val available = b.quantityRemaining
                        db.purchaseBatchDao().updateBatch(
                            b.copy(quantityRemaining = 0.0, isSynced = false)
                        )
                        totalReduced += available
                    }
                } else {
                    totalReduced += line.quantity
                }
            }

            db.purchaseBatchDao().clearEmptyBatches(productId)
            totalReduced
        }
    }

    /**
     * Realigns `inventory.averageCost` to SUM(remainingQty × unitCost) /
     * SUM(remainingQty) across the batch ledger.
     *
     * NOT called automatically anywhere anymore as of the moving-average
     * redesign (Phase 1) — average cost is now purely event-driven
     * (purchase blend, sales-return blend at the bill's recorded rate).
     * Kept as a manual/diagnostic tool only: useful from a debug menu or
     * a one-off data-repair script if a product's batches and average
     * cost are ever found to have drifted apart for some other reason,
     * but nothing in the normal sale/purchase/return flow should call
     * this anymore. If you're about to add a new call site, stop and
     * check whether what you actually want is one of the explicit
     * formulas in InventoryManager/PurchaseReturnViewModel instead.
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
     * Issue 14 fix (now handles both directions — see Issue 16). [consumeFifo]
     * can come up short of the caller's requested quantity — either because
     * it threw partway through (a bug, a transient error) or because the
     * real batches simply didn't have enough remaining to cover the
     * reduction (drift already present from something earlier). Either way,
     * `inventory.currentStock` has already been changed by the full amount,
     * so from this point on SUM(batch.quantityRemaining) can end up either
     * UNDER or OVER what currentStock says, and the mismatch would
     * otherwise persist forever: [ensureSyntheticBatch] deliberately
     * refuses to touch a product that already has a real batch, and it also
     * never runs at all once currentStock is 0 (see [InventoryManager.clearStock]),
     * so nothing else can repair either direction on its own.
     *
     * Call this right after [consumeFifo] (or anywhere else the ledger
     * might have drifted):
     *   • Batches SHORT of currentStock (understating stock) — backfill a
     *     synthetic correction batch for the shortfall, priced at the
     *     current average cost (the best estimate available, since we
     *     don't know which historical batch the missing units really
     *     belonged to).
     *   • Batches IN EXCESS of currentStock (overstating stock — e.g.
     *     [InventoryManager.clearStock]'s drain came up short, leaving
     *     phantom remaining quantity behind on a product that's actually
     *     at zero) — drain the excess via FIFO so the ledger can never
     *     report more stock than the shop actually has.
     */
    suspend fun reconcileDrift(db: AppDatabase, productId: Int) {
        db.purchaseBatchDao().purgeDriftCorrectionBatches()

        val totals = db.purchaseBatchDao().getValuationTotals(productId)
        val inventory = db.inventoryDao().getInventory(productId) ?: return

        val drift = inventory.currentStock - totals.totalQty
        if (kotlin.math.abs(drift) <= 0.001) return  // already reconciled

        if (drift > 0.0) {
            // Batches understate stock — backfill the shortfall.
            android.util.Log.w(
                "InventoryValuation",
                "Drift correction: product=$productId batches were short by " +
                    "$drift unit(s) vs currentStock=${inventory.currentStock} — " +
                    "backfilling at avgCost=${inventory.averageCost}"
            )
            db.purchaseBatchDao().insertBatch(
                PurchaseBatch(
                    productId = productId,
                    purchaseInvoiceId = null,
                    supplierName = null,
                    supplierGstin = null,
                    invoiceNumber = null,
                    batchCode = "DRIFT-CORRECTION",
                    quantityPurchased = drift,
                    quantityRemaining = drift,
                    unitCostExcludingTax = inventory.averageCost,
                    gstPercent = 0.0,
                    cgstPercent = 0.0,
                    sgstPercent = 0.0,
                    igstPercent = 0.0,
                    invoiceValue = drift * inventory.averageCost,
                    taxableValue = drift * inventory.averageCost,
                    createdAt = 0L,
                    isSynced = true   // synthetic — never push to backend
                )
            )
        } else if (drift < 0.0) {
            // Batches overstate stock — phantom leftover quantity. Drain via FIFO.
            val excess = -drift
            consumeFifo(db, productId, excess)
        }

        // Moving-average redesign (Phase 1): drift reconciliation now only
        // ever touches batch QUANTITY (backfilling or draining above) —
        // never inventory.averageCost. Cost is purely event-driven now
        // (purchase blend, sales-return blend), so a quantity self-heal
        // has no reason to move it. The synthetic backfill batch above
        // still records a cost estimate (the current average, same as
        // before) purely so the batch ledger has *something* sensible on
        // that row for invoice/quantity-tracking purposes — that estimate
        // is never read back into inventory.averageCost.
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

        // Guard: if ANY non-MIGRATION batch already exists, the product has a
        // real batch ledger. Do NOT wipe it. Only create MIGRATION when the
        // ledger is truly empty — that's the only time it's needed.
        val allBatches = db.purchaseBatchDao().getAllBatches(productId)
        if (allBatches.any { it.batchCode != "MIGRATION" }) return

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
     * Records a fresh purchase batch (quantity/invoice tracking only).
     * Used by [InventoryManager.addStock] and
     * [com.example.easy_billing.repository.PurchaseRepository].
     *
     * Moving-average redesign (Phase 1): this used to call
     * recomputeAvgFromBatches() right after inserting, which meant a
     * purchase's average cost was actually decided by summing the WHOLE
     * batch ledger — not by addStock's own weighted-average formula. That
     * doesn't just look wrong now, it's now built to overwrite
     * addStock's careful blend the moment a purchase happens on a
     * product that already has real batches. addStock already writes
     * the correct new average to the inventory row (its own explicit
     * formula, using the current — possibly frozen — average as the
     * starting point) BEFORE calling this function, so this must not
     * touch averageCost at all anymore, or it would silently undo that.
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
            db.purchaseBatchDao().insertBatch(
                batch.copy(quantityRemaining = batch.quantityPurchased)
            )
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
