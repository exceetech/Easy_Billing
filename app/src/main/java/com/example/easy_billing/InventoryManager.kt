package com.example.easy_billing

import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Inventory
import com.example.easy_billing.db.InventoryLog
import com.example.easy_billing.db.InventoryTransaction
import com.example.easy_billing.db.PurchaseBatch

object InventoryManager {

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
        batchMeta: StockBatchMeta? = null
    ) {

        require(productId > 0) { "Invalid productId" }
        require(quantity > 0) { "Invalid quantity" }
        require(costPrice >= 0) { "Cost price cannot be negative" }

        val inventoryDao = db.inventoryDao()
        val transactionDao = db.inventoryTransactionDao()
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

        // 🔥 LOG (ONLY PLACE WHERE LOG IS CREATED)
        db.inventoryLogDao().insert(
            InventoryLog(
                productId = productId,
                type = "ADD",
                quantity = quantity,
                price = costPrice,
                date = System.currentTimeMillis()
            )
        )

        // 🔥 TRANSACTION
        transactionDao.insert(
            InventoryTransaction(
                productId = productId,
                type = "PURCHASE",
                quantity = quantity,
                costPrice = costPrice,
                totalCost = quantity * costPrice
            )
        )

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
                    createdAt = System.currentTimeMillis(),
                    isSynced = false
                )
            )
        }
    }

    // ================= REDUCE STOCK =================
    suspend fun reduceStock(
        db: AppDatabase,
        productId: Int,
        quantity: Double,
        type: String = "SALE", // SALE / LOSS / ADJUST
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
    ) {

        require(productId > 0) { "Invalid productId" }
        require(quantity > 0) { "Invalid quantity" }

        val inventoryDao = db.inventoryDao()
        val transactionDao = db.inventoryTransactionDao()

        val inventory = inventoryDao.getInventory(productId)
            ?: throw Exception("No inventory found")

        if (inventory.currentStock < quantity) {
            throw Exception("Insufficient stock")
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

        // 🔥 LOG (VERY IMPORTANT)
        db.inventoryLogDao().insert(
            InventoryLog(
                productId = productId,
                type = type, // SALE / LOSS / ADJUST
                quantity = quantity,
                price = avgCost,
                date = System.currentTimeMillis()
            )
        )

        // 🔥 TRANSACTION
        transactionDao.insert(
            InventoryTransaction(
                productId = productId,
                type = type,
                quantity = quantity,
                costPrice = avgCost,
                totalCost = quantity * avgCost
            )
        )

        // ── Hybrid batch-based inventory (v20) ──
        // Debit the canonical batch ledger so SUM(batch.quantityRemaining)
        // stays in lockstep with inventory.currentStock. consumeFifo walks
        // oldest-first and calls recomputeAvgFromBatches internally, which
        // overrides the avgCost we tentatively wrote a few lines above
        // with the correct value derived from what's actually left on the
        // shelf. Without this call the batch table fills up but never
        // decrements — which is the bug this whole hybrid model exists
        // to fix.
        if (!skipBatchConsume) {
            runCatching {
                InventoryValuation.consumeFifo(db, productId, quantity)
            }.onFailure {
                android.util.Log.w(
                    "InventoryManager",
                    "FIFO consume skipped for product=$productId qty=$quantity: ${it.message}"
                )
            }
        }
    }

    // ================= CLEAR STOCK =================
    suspend fun clearStock(
        db: AppDatabase,
        productId: Int,
        type: String = "ADJUST", // or LOSS
        // v20 batch drain — true skips the per-batch zero-out so the
        // batch-aware repository methods (which already debited
        // specific batches) don't get a second pass here.
        skipBatchConsume: Boolean = false
    ) {

        require(productId > 0) { "Invalid productId" }

        val inventoryDao = db.inventoryDao()
        val transactionDao = db.inventoryTransactionDao()

        val inventory = inventoryDao.getInventory(productId)
            ?: throw Exception("No inventory found")

        val oldStock = inventory.currentStock
        val avgCost = inventory.averageCost

        if (oldStock <= 0) return

        inventoryDao.update(
            inventory.copy(
                currentStock = 0.0,
                averageCost = 0.0,
                isActive = true,
                isSynced = false
            )
        )

        // 🔥 LOG
        db.inventoryLogDao().insert(
            InventoryLog(
                productId = productId,
                type = type,
                quantity = oldStock,
                price = avgCost,
                date = System.currentTimeMillis()
            )
        )

        // 🔥 TRANSACTION
        transactionDao.insert(
            InventoryTransaction(
                productId = productId,
                type = "CLEAR",
                quantity = oldStock,
                costPrice = avgCost,
                totalCost = oldStock * avgCost
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
        }
    }

    // ================= GET STOCK =================
    suspend fun getTotalStock(db: AppDatabase, productId: Int): Double {
        return db.inventoryDao().getTotalQuantity(productId) ?: 0.0
    }
}