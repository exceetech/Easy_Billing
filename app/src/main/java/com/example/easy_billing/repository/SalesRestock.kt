package com.example.easy_billing.repository

import androidx.room.withTransaction
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.correction.FifoAllocator
import com.example.easy_billing.correction.PdnClaims
import com.example.easy_billing.db.CostCorrectionClaim
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.PurchaseBatch
import com.example.easy_billing.util.appNow

/**
 * Stage 3B: put units that come back from a customer (sales return or a
 * cancelled sale) back against the purchase bill(s) they were sold from,
 * instead of an anonymous "SALES_RETURN" batch.
 *
 * The moving-average cost is untouched: [InventoryManager.addStock] still
 * blends at [costPrice] exactly as before. Only the BATCH side changes, so
 * "units of bill X still on the shelf" stays true after a return.
 *
 * Units that cannot be tied to a live purchase bill (old sales with no
 * allocation rows, legacy/drift stock, a cancelled purchase) restock the
 * old way, through [batchMeta].
 */
object SalesRestock {

    suspend fun restock(
        db: AppDatabase,
        billItemId: Int,
        productId: Int,
        quantity: Double,
        costPrice: Double,
        batchMeta: InventoryManager.StockBatchMeta,
        logType: String
    ) {
        db.withTransaction {
            val dao = db.saleBatchAllocationDao()
            val rows = if (billItemId > 0) dao.getForBillLine(billItemId, productId) else emptyList()
            val plan = FifoAllocator.planRestore(
                rows.map {
                    FifoAllocator.OutRow(
                        it.id, it.purchaseInvoiceId, it.batchId, it.quantity,
                        it.quantityRestored, it.unitCostNet, it.unitCostGross
                    )
                },
                quantity
            )

            val existingBatches = db.purchaseBatchDao().getAllBatches(productId)
            val templates = ArrayList<PurchaseBatch>()
            var attributedQty = 0.0
            // Claims are collected first and inserted after the loop, so a
            // shortfall below zero cost (bug C) can be taken out of them.
            val pendingClaims = ArrayList<CostCorrectionClaim>()
            val pendingQtyByPool = HashMap<Int, Double>()
            val claimSource = if (logType == InventoryManager.LogType.CANCEL_RESTOCK) "BILL_CANCEL" else "SALES_RETURN"
            val rowsById = rows.associateBy { it.id }

            for (r in plan.restores) {
                val inv = r.purchaseInvoiceId
                val liveInvoice = inv != null && db.purchaseDao().getById(inv)?.isCancelled == false
                if (!liveInvoice) {
                    // Cannot go back to a bill: counted as leftover, but still
                    // marked restored so the row is not used twice.
                    dao.addRestored(r.rowId, r.quantity)
                    continue
                }
                // Prefer the bill's CURRENT batch (cost may have been corrected
                // since the sale). When it is gone (sold out and cleared), rebuild
                // the details from the purchase bill itself, with its corrected
                // rates, instead of the cost frozen at sale time.
                val live = existingBatches.firstOrNull { it.purchaseInvoiceId == inv }
                val purchase = if (live == null) db.purchaseDao().getById(inv!!) else null
                val billRates = if (live == null && purchase != null) {
                    val lines = db.purchaseItemDao().getByPurchase(inv!!).filter { it.productId == productId }
                    if (lines.size == 1) PurchaseCorrectionRepository.ratesFor(db, inv, lines[0]) else null
                } else null

                val net = live?.unitCostExcludingTax ?: billRates?.net ?: r.unitCostNet
                val gross = when {
                    live != null ->
                        if (live.invoiceValue > 0.0 && live.quantityPurchased > 0.0)
                            live.invoiceValue / live.quantityPurchased else r.unitCostGross
                    billRates != null -> billRates.gross
                    else -> r.unitCostGross
                }
                val cgst = live?.cgstPercent ?: billRates?.cgstPct ?: 0.0
                val sgst = live?.sgstPercent ?: billRates?.sgstPct ?: 0.0
                val igst = live?.igstPercent ?: billRates?.igstPct ?: 0.0

                templates += PurchaseBatch(
                    productId = productId,
                    purchaseInvoiceId = inv,
                    supplierName = live?.supplierName ?: purchase?.supplierName,
                    supplierGstin = live?.supplierGstin ?: purchase?.supplierGstin,
                    invoiceNumber = live?.invoiceNumber ?: purchase?.invoiceNumber,
                    batchCode = "RESTORED-$inv",
                    quantityPurchased = r.quantity,
                    quantityRemaining = r.quantity,
                    unitCostExcludingTax = net,
                    gstPercent = live?.gstPercent ?: (cgst + sgst + igst),
                    cgstPercent = cgst,
                    sgstPercent = sgst,
                    igstPercent = igst,
                    invoiceValue = gross * r.quantity,
                    taxableValue = net * r.quantity,
                    // Keep the bill's own place in the FIFO queue.
                    createdAt = live?.createdAt ?: purchase?.createdAt ?: appNow(),
                    isSynced = false
                )
                attributedQty += r.quantity
                dao.addRestored(r.rowId, r.quantity)

                // Stage 3C: pens sold before a price correction take back the
                // part of it that was booked to profit when they were "gone".
                val soldAt = rowsById[r.rowId]?.createdAt ?: Long.MAX_VALUE
                val pools = db.costCorrectionDao().poolsFor(inv!!, productId).map { pl ->
                    PdnClaims.Pool(
                        pl.id, pl.noteId, pl.noteDate, pl.goneQty,
                        db.costCorrectionDao().claimedQty(pl.id) + (pendingQtyByPool[pl.id] ?: 0.0),
                        pl.deltaGross
                    )
                }
                val claimPlan = PdnClaims.plan(soldAt, r.quantity, pools)
                for (c in claimPlan.claims) {
                    pendingQtyByPool[c.poolId] = (pendingQtyByPool[c.poolId] ?: 0.0) + c.qty
                    pendingClaims += CostCorrectionClaim(
                        allocationId = r.rowId, poolId = c.poolId, noteId = c.noteId,
                        qty = c.qty, amount = c.amount, claimDate = appNow(),
                        sourceType = claimSource, sourceId = billItemId
                    )
                }
            }

            // Bug C: the restocked pens cannot cost less than zero. If the
            // claims would push the cost below zero, take the excess out of the
            // loss claims so the unbooked value does not vanish from the books.
            val frozenTotal = costPrice * quantity
            var claimAmountTotal = pendingClaims.sumOf { it.amount }
            var excess = claimAmountTotal - frozenTotal
            if (excess > 1e-9) {
                for (i in pendingClaims.indices.reversed()) {
                    if (excess <= 1e-9) break
                    val c = pendingClaims[i]
                    if (c.amount <= 0.0) continue
                    val cut = minOf(c.amount, excess)
                    pendingClaims[i] = c.copy(amount = c.amount - cut)
                    excess -= cut
                }
                claimAmountTotal = pendingClaims.sumOf { it.amount }
            }
            for (c in pendingClaims) db.costCorrectionDao().insertClaim(c)
            // each claim moves the restock cost by minus its amount
            val claimCostAdjust = -claimAmountTotal

            // The leftover (and unattributable units) use the caller's meta,
            // sized to what is left after the restored batches.
            val rest = quantity - attributedQty
            val metaForRest = if (rest > 1e-9 && quantity > 0.0) {
                val f = rest / quantity
                batchMeta.copy(
                    invoiceValue = batchMeta.invoiceValue * f,
                    taxableValue = batchMeta.taxableValue * f
                )
            } else batchMeta

            // Claimed pens come back at the corrected cost.
            val adjustedCost = if (quantity > 0.0)
                ((costPrice * quantity + claimCostAdjust) / quantity).coerceAtLeast(0.0)
            else costPrice

            InventoryManager.addStock(
                db = db,
                productId = productId,
                quantity = quantity,
                costPrice = adjustedCost,
                batchMeta = metaForRest,
                logType = logType,
                restoredBatches = templates.ifEmpty { null }
            )
        }
    }
}
