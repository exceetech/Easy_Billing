package com.example.easy_billing

import com.example.easy_billing.util.appNow

import androidx.room.withTransaction
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Inventory
import com.example.easy_billing.db.InventoryLog
import com.example.easy_billing.db.PurchaseBatch

object InventoryManager {

    /**
     * The full set of [InventoryLog.type] values this app writes, in one
     * place. Added per Audit Round 2, P-4/P-2 (2026-07-23): before this,
     * every call site wrote its own raw string literal, and the backend's
     * profit report silently fell out of sync with three of these types
     * (RETURN, ADJUST, CANCEL_RESTOCK) for a long time before anyone
     * noticed — see pos-backend/app/routes/profit_routes.py and
     * app/models/inventory_log.py, which now cross-reference this list.
     * If you add a new type, it MUST also be reflected there.
     */
    object LogType {
        /** Stock purchased in (also used as [addStock]'s default). */
        const val ADD = "ADD"
        /** Stock sold. Written for history only — profit_routes.py
         *  deliberately does NOT read these; SaleItem is the source of
         *  truth for sold quantity/revenue/cost. */
        const val SALE = "SALE"
        /** Stock scrapped / lost. */
        const val LOSS = "LOSS"
        /** Reserved for a future genuine additive case (customer returns an
         *  item to the shop). Not currently written anywhere in this app —
         *  do NOT reuse this for supplier returns, see [PURCHASE_RETURN]. */
        const val RETURN = "RETURN"
        /** Customer returning stock to the shop (Sales Return via Credit Note). 
         *  Additive. Handled properly by the backend for profit analysis. */
        const val SALES_RETURN = "SALES_RETURN"
        /** Stock returned to a supplier (leaves the shop). Subtractive —
         *  kept distinct from [RETURN] because the backend buckets log
         *  types by a fixed additive/subtractive sign, and a single
         *  "RETURN" type can't mean both directions. */
        const val PURCHASE_RETURN = "PURCHASE_RETURN"
        /** Manual stock-count correction (InventoryManager.resetStock) —
         *  NOTE: unlike every other type here, the logged quantity is the
         *  ABSOLUTE resulting stock count, not a delta. */
        const val ADJUST = "ADJUST"
        /** Stock restored to inventory when a bill is cancelled. */
        const val CANCEL_RESTOCK = "CANCEL_RESTOCK"
    }

    /**
     * Optional metadata accompanying a stock-in event. Direct
     * add-stock callers leave this null; [PurchaseRepository] passes
     * a fully-populated record so the batch row carries the
     * supplier / invoice / GST split.
     *
     * Per the architecture spec, [unitCostExcludingTax] must always
     * be the cost net of GST. When absent we fall back to the
     * [addStock] `costPrice` argument.
     */
    data class StockBatchMeta(
        val purchaseInvoiceId: Int? = null,
        val supplierName: String? = null,
        val supplierGstin: String? = null,
        val invoiceNumber: String? = null,
        val batchCode: String? = null,
        val unitCostExcludingTax: Double? = null,
        val gstPercent: Double = 0.0,
        val cgstPercent: Double = 0.0,
        val sgstPercent: Double = 0.0,
        val igstPercent: Double = 0.0,
        val invoiceValue: Double = 0.0,
        val taxableValue: Double = 0.0
    )

    // ================= RESET STOCK =================
    suspend fun resetStock(
        db: AppDatabase,
        productId: Int,
        quantity: Double,
        costPrice: Double
    ) {
        require(productId > 0) { "Invalid productId" }
        require(quantity >= 0) { "Invalid quantity" }
        require(costPrice >= 0) { "Cost price cannot be negative" }

        // INV-1 fix: the read (getInventoryIncludingInactive) and the write
        // (insert/update) must be atomic. Room serializes concurrent
        // withTransaction blocks against the same rows, so a second caller's
        // read inside its own transaction always sees this transaction's
        // committed result rather than stale data — closing the lost-update
        // race that let two near-simultaneous stock changes silently
        // overwrite one another.
        db.withTransaction {
            val inventoryDao = db.inventoryDao()
            val productDao = db.productDao()

            val product = productDao.getById(productId)
            val isPurchased = product?.isPurchased ?: true

            val existing = inventoryDao.getInventoryIncludingInactive(productId)
            val finalAvg = if (isPurchased) costPrice else 0.0

            if (existing == null) {
                inventoryDao.insert(
                    Inventory(
                        productId = productId,
                        currentStock = quantity,
                        averageCost = finalAvg,
                        isActive = true,
                        isSynced = false
                    )
                )
            } else {
                inventoryDao.update(
                    existing.copy(
                        currentStock = quantity,
                        averageCost = finalAvg,
                        isActive = true,
                        isSynced = false
                    )
                )
            }

            // Log to inventory_logs
            db.inventoryLogDao().insert(
                InventoryLog(
                    productId = productId,
                    type = LogType.ADJUST,
                    quantity = quantity,
                    price = costPrice,
                    date = appNow(),
                    isSynced = false,
                    // Avg-cost audit, Fix 2: resetStock's final average cost
                    // is decided right here, with nothing further mutating
                    // it in this transaction — so the value written to
                    // inventory IS the resulting average cost.
                    resultingAverageCost = finalAvg
                )
            )
        }
    }

    // ================= ADD STOCK =================
    suspend fun addStock(
        db: AppDatabase,
        productId: Int,
        quantity: Double,
        costPrice: Double,
        /**
         * Optional batch metadata. When provided, the recorded
         * [PurchaseBatch] carries the full supplier / GST split —
         * which is what supplier-return reversals need. When null we
         * still record a batch (so the FIFO ledger stays in sync)
         * but with just productId / quantity / costPrice.
         *
         * Default null preserves the existing 4-arg call sites
         * across the codebase (sales, manual flows, tests).
         */
        batchMeta: StockBatchMeta? = null,
        logType: String = LogType.ADD
    ) {

        require(productId > 0) { "Invalid productId" }
        require(quantity > 0) { "Invalid quantity" }
        require(costPrice >= 0) { "Cost price cannot be negative" }

        // INV-1 fix: read + weighted-average compute + write + log + batch
        // record must all happen as one atomic unit — otherwise a
        // concurrent addStock/reduceStock for the same product can read the
        // same "old" stock/avg this call read, and one of the two writes
        // silently overwrites the other. Room nests withTransaction calls
        // safely, so the ensureSyntheticBatch/recordBatch calls below (which
        // use their own withTransaction internally) join this same
        // transaction rather than opening a separate one.
        db.withTransaction {
            val inventoryDao = db.inventoryDao()
            val productDao = db.productDao()

            val product = productDao.getById(productId)
            val isPurchased = product?.isPurchased ?: true // default to true to be safe

            val existing = inventoryDao.getInventory(productId)

            // ── Hybrid batch-based inventory (v21) ──
            // Before touching the inventory row, seed a synthetic batch
            // for any pre-existing legacy stock. Without this, a product
            // that has on-hand stock but no batches yet (e.g. created
            // before v21) would have its first new batch *overwrite* the
            // weighted-average via the post-insert recompute. Seeding
            // first preserves the (oldStock × oldAvg) leg of the average.
            if (isPurchased && existing != null && existing.currentStock > 0.0) {
                InventoryValuation.ensureSyntheticBatch(db, productId)
            }

            if (existing == null) {

                // 🔥 FIRST STOCK ENTRY
                inventoryDao.insert(
                    Inventory(
                        productId = productId,
                        currentStock = quantity,
                        averageCost = if (isPurchased) costPrice else 0.0,
                        isActive = true,
                        isSynced = false
                    )
                )

            } else {

                val oldStock = existing.currentStock
                val oldAvg = existing.averageCost

                val newStock = oldStock + quantity

                // Logic:
                // 1. If not purchased -> avg cost is ALWAYS 0.
                // 2. If purchased and new cost <= 0 -> preserve old avg.
                // 3. If purchased and new cost > 0 -> recompute weighted average.
                val newAvg = when {
                    !isPurchased -> 0.0
                    costPrice <= 0.0 -> oldAvg
                    oldStock <= 0.0 -> costPrice
                    newStock <= 0.0 -> costPrice
                    else -> ((oldStock * oldAvg) + (quantity * costPrice)) / newStock
                }

                inventoryDao.update(
                    existing.copy(
                        currentStock = newStock,
                        averageCost = newAvg,
                        isActive = true,
                        isSynced = false
                    )
                )
            }

            // ── Hybrid batch-based inventory (v21) ──
            // Always record a purchase batch so SUM(quantityRemaining)
            // stays in lockstep with inventory.currentStock and supplier
            // returns can value at the original batch cost. For non-GST
            // / manual add-stock flows the batch is minimal — productId,
            // quantity, costPrice — which is still enough for FIFO walks.
            if (isPurchased) {
                val unitCostNet = batchMeta?.unitCostExcludingTax
                    ?: costPrice  // direct add-stock: no GST split known
                InventoryValuation.recordBatch(
                    db = db,
                    batch = PurchaseBatch(
                        productId = productId,
                        purchaseInvoiceId = batchMeta?.purchaseInvoiceId,
                        supplierName = batchMeta?.supplierName,
                        supplierGstin = batchMeta?.supplierGstin,
                        invoiceNumber = batchMeta?.invoiceNumber,
                        batchCode = batchMeta?.batchCode,
                        quantityPurchased = quantity,
                        quantityRemaining = quantity,
                        unitCostExcludingTax = unitCostNet,
                        gstPercent = batchMeta?.gstPercent ?: 0.0,
                        cgstPercent = batchMeta?.cgstPercent ?: 0.0,
                        sgstPercent = batchMeta?.sgstPercent ?: 0.0,
                        igstPercent = batchMeta?.igstPercent ?: 0.0,
                        invoiceValue = batchMeta?.invoiceValue ?: (quantity * costPrice),
                        taxableValue = batchMeta?.taxableValue ?: (quantity * unitCostNet),
                        createdAt = appNow(),
                        isSynced = false
                    )
                )
            }

            // INV-3 fix: recordBatch (like every mutation path) can leave
            // purchase_batches out of step with currentStock in edge cases
            // (e.g. a batch insert failing after the inventory row already
            // moved). Reconcile immediately so a stock-in can never leave
            // silent drift behind, matching reduceStock/clearStock's own
            // self-heal.
            runCatching {
                InventoryValuation.reconcileDrift(db, productId)
            }.onFailure {
                android.util.Log.w(
                    "InventoryManager",
                    "Drift reconcile failed for product=$productId: ${it.message}"
                )
            }

            // 🔥 LOG (ONLY PLACE WHERE LOG IS CREATED)
            // Avg-cost audit, Fix 2: written LAST, after recordBatch and
            // reconcileDrift have both had their chance to touch
            // averageCost — re-reading the row here (instead of using the
            // newAvg local computed earlier) guarantees resultingAverageCost
            // is the true final value for this transaction, not a
            // snapshot from partway through it.
            val finalAvg = inventoryDao.getInventory(productId)?.averageCost ?: 0.0
            db.inventoryLogDao().insert(
                InventoryLog(
                    productId = productId,
                    type = logType,
                    quantity = quantity,
                    price = costPrice,
                    date = appNow(),
                    resultingAverageCost = finalAvg
                )
            )
        }
    }

    // ================= REDUCE STOCK =================
    suspend fun reduceStock(
        db: AppDatabase,
        productId: Int,
        quantity: Double,
        type: String = LogType.SALE, // SALE / LOSS / ADJUST / RETURN
        /**
         * The new InventoryReductionRepository methods
         * (returnToSupplierByBatches, reduceWithFifo) already
         * consume specific batches before calling reduceStock —
         * passing true suppresses the FIFO consume here so the
         * same units aren't debited twice. Default false so every
         * other caller (sales, manual scrap, etc.) keeps the
         * inventory row in sync with the batch ledger.
         */
        skipBatchConsume: Boolean = false
    ): Double {

        require(productId > 0) { "Invalid productId" }
        require(quantity > 0) { "Invalid quantity" }

        // INV-1 fix: read + insufficient-stock check + write + log + batch
        // consume must all happen as one atomic unit. Without this, two
        // concurrent reduceStock calls for the same product can both read
        // the same currentStock, both pass the "enough stock" check against
        // that same stale number, and the second write silently overwrites
        // the first — losing one of the two deductions and leaving stock
        // higher than it should be (or, depending on timing, applying only
        // one of two decrements users saw confirmed on screen).
        return db.withTransaction {
            val inventoryDao = db.inventoryDao()

            val inventory = inventoryDao.getInventory(productId)
                ?: throw Exception("No inventory found")

            if (inventory.currentStock < quantity) {
                throw Exception("Insufficient stock")
            }

            // ── Hybrid batch-based inventory (v21) ──
            // Ensure batches are in sync before we mutate the inventory row!
            if (inventory.currentStock > 0.0) {
                InventoryValuation.ensureSyntheticBatch(db, productId)
            }

            val newStock = inventory.currentStock - quantity
            val avgCost = inventory.averageCost

            inventoryDao.update(
                inventory.copy(
                    currentStock = newStock,
                    averageCost = if (newStock <= 0.0) 0.0 else inventory.averageCost,
                    isActive = true,
                    isSynced = false
                )
            )

            // ── Hybrid batch-based inventory (v20) ──
            // Debit the canonical batch ledger so SUM(batch.quantityRemaining)
            // stays in lockstep with inventory.currentStock. consumeFifo walks
            // oldest-first purely to track remaining quantity per batch/invoice
            // (needed for purchase-return validation) — as of the moving-average
            // redesign (Phase 1), it no longer touches averageCost. The
            // averageCost written a few lines above (frozen — unchanged unless
            // stock hit zero) is the real, final value for this sale; nothing
            // below this point overrides it. Without the FIFO call below the
            // batch table would still fill up but never decrement, which would
            // break purchase-return validation (how much of THIS invoice is
            // still available to return) even though it no longer affects cost.
            //
            // Cost basis returned to the caller: `avgCost * quantity` — the
            // average cost AT THE TIME OF THIS SALE (captured above, before
            // this transaction's own mutation). A short-lived Phase 3 of the
            // moving-average redesign tried returning the true per-batch FIFO
            // cost instead (e.g. 100@10 + 1@15 for a 101-unit sale spanning
            // two batches), reasoning that it was more "precise." That broke
            // consistency with Phase 1's own accounting: once a sale is
            // defined to remove inventory VALUE at exactly avgCost × quantity
            // (frozen, by definition), that number — not a separately-derived
            // per-batch figure — has to be what the bill records as cost of
            // goods sold, or the P&L (bill's cost) and the balance sheet
            // (inventory value drop) disagree with each other on the very
            // same sale. Reverted back to the simple, consistent figure.
            val trueCost = avgCost * quantity

            if (!skipBatchConsume) {
                runCatching {
                    InventoryValuation.consumeFifo(db, productId, quantity)
                }.onFailure {
                    android.util.Log.w(
                        "InventoryManager",
                        "FIFO consume skipped for product=$productId qty=$quantity: ${it.message}"
                    )
                }

                // Issue 14: whether the FIFO consume above threw, ran short, or
                // fully succeeded, verify the batch ledger still covers
                // currentStock and self-heal immediately if not. Without this,
                // a short/failed consume leaves the ledger silently overstating
                // remaining stock forever — ensureSyntheticBatch won't touch a
                // product that already has real batches, so nothing else would
                // ever repair it.
                runCatching {
                    InventoryValuation.reconcileDrift(db, productId)
                }.onFailure {
                    android.util.Log.w(
                        "InventoryManager",
                        "Drift reconcile failed for product=$productId: ${it.message}"
                    )
                }
            }

            // 🔥 LOG (VERY IMPORTANT)
            // Moving-average redesign (Phase 1): consumeFifo/reconcileDrift
            // no longer touch averageCost at all (they only track batch
            // quantity now), so re-reading here just confirms `avgCost`
            // (the frozen value written above) is still the final value —
            // kept as a re-read rather than reusing `avgCost` directly so
            // this stays correct even if a future change ever does need to
            // adjust cost somewhere in between.
            val finalAvg = inventoryDao.getInventory(productId)?.averageCost ?: avgCost
            db.inventoryLogDao().insert(
                InventoryLog(
                    productId = productId,
                    type = type, // SALE / LOSS / ADJUST
                    quantity = quantity,
                    price = avgCost,
                    date = appNow(),
                    resultingAverageCost = finalAvg
                )
            )

            trueCost
        }
    }

    // ================= CLEAR STOCK =================
    suspend fun clearStock(
        db: AppDatabase,
        productId: Int,
        // Report 5 fix: was LogType.ADJUST by default. ADJUST's contract
        // (see the LogType block above) is that the logged `quantity` is
        // the ABSOLUTE resulting stock count — that's what resetStock()
        // writes. clearStock() writes `quantity = oldStock` below (the
        // amount being removed, not the new total, which is always 0) —
        // tagging that as ADJUST would tell the backend "set stock back to
        // oldStock", the exact opposite of clearing it, ever since the
        // backend's ADJUST handling was fixed to treat the logged quantity
        // as absolute. LOSS is a safe default: a real delta type, correctly
        // subtracted server-side. Every current call site already passes
        // LOSS or RETURN explicitly — this default only matters for future
        // callers, but it must never be ADJUST.
        type: String = LogType.LOSS, // or RETURN
        // v20 batch drain — true skips the per-batch zero-out so the
        // batch-aware repository methods (which already debited
        // specific batches) don't get a second pass here.
        skipBatchConsume: Boolean = false
    ) {

        require(productId > 0) { "Invalid productId" }

        // INV-1 fix: same atomicity requirement as reduceStock/addStock —
        // read + write + log + batch drain as one unit so a concurrent
        // mutation on the same product can't race this one.
        db.withTransaction {
            val inventoryDao = db.inventoryDao()

            val inventory = inventoryDao.getInventory(productId)
                ?: throw Exception("No inventory found")

            val oldStock = inventory.currentStock
            val avgCost = inventory.averageCost

            if (oldStock <= 0) return@withTransaction

            // ── Hybrid batch-based inventory (v21) ──
            // Ensure batches are in sync before we mutate the inventory row!
            if (oldStock > 0.0) {
                InventoryValuation.ensureSyntheticBatch(db, productId)
            }

            inventoryDao.update(
                inventory.copy(
                    currentStock = 0.0,
                    averageCost = 0.0,
                    isActive = true,
                    isSynced = false
                )
            )

            // 🔥 LOG
            // Avg-cost audit, Fix 2: clearStock always zeroes averageCost
            // above and nothing after this point can raise it back off
            // zero (currentStock is 0, so reconcileDrift's own
            // recompute-from-batches also lands on 0) — so 0.0 is already
            // the true final value, no re-read needed here.
            db.inventoryLogDao().insert(
                InventoryLog(
                    productId = productId,
                    type = type,
                    quantity = oldStock,
                    price = avgCost,
                    date = appNow(),
                    resultingAverageCost = 0.0
                )
            )

            // v20 clearStock batch drain — zero out every remaining batch
            // for this product so SUM(remainingQty) matches the now-empty
            // inventory row. Uses consumeFifo with the full remaining
            // total; if the row was already 0 this is a no-op.
            if (!skipBatchConsume) {
                val remaining = db.purchaseBatchDao()
                    .getValuationTotals(productId).totalQty
                if (remaining > 0.0) {
                    runCatching {
                        InventoryValuation.consumeFifo(db, productId, remaining)
                    }.onFailure {
                        android.util.Log.w(
                            "InventoryManager",
                            "clearStock batch drain failed for product=$productId: ${it.message}"
                        )
                    }
                }

                // Issue 16: if the drain above threw or came up short, phantom
                // batch quantity can be left behind on a product that's now at
                // zero stock. ensureSyntheticBatch never runs once currentStock
                // is 0 (both its own call sites in addStock/reduceStock are
                // guarded by currentStock > 0), so nothing else would ever
                // notice or drain that leftover — it would sit there inflating
                // batch-derived valuation reports indefinitely. reconcileDrift
                // catches this: currentStock is 0 here, so any batch total left
                // over is by definition pure excess and gets drained.
                runCatching {
                    InventoryValuation.reconcileDrift(db, productId)
                }.onFailure {
                    android.util.Log.w(
                        "InventoryManager",
                        "Drift reconcile failed for product=$productId: ${it.message}"
                    )
                }
            }
        }
    }

    // ================= GET STOCK =================
    suspend fun getTotalStock(db: AppDatabase, productId: Int): Double {
        return db.inventoryDao().getTotalQuantity(productId) ?: 0.0
    }
}