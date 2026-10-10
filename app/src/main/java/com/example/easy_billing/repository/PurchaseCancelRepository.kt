package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.getNetQtyOut
import com.example.easy_billing.db.heldQuantity
import com.example.easy_billing.util.appNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cancelling a credit purchase.
 *
 * A cancel is nothing more than **"return everything still on hand from this
 * purchase, in one action"** — plus an is_cancelled status flag. Framing it as
 * a bulk return means the inventory removal and the input-tax-credit reversal
 * ride on the exact same machinery a normal purchase return already uses
 * ([InventoryReductionRepository.returnToSupplierByBatches]); nothing about ITC
 * is reinvented here, so it can't drift from how returns behave.
 *
 * Hard rule: **cancel is blocked the moment any unit from this purchase has
 * been sold or scrapped.** You can't return goods you've already sold on.
 * Prior returns, additions and quantity/price corrections are fine — cancel
 * just sweeps up whatever remains. "Sold" is the pens this bill still owns
 * (billed − net notes) that are no longer on the shelf (its batch counters);
 * if that is more than a rounding hair we block rather than risk corrupting
 * stock. (Phase 4 rule: a prior return is no longer counted as consumption.)
 *
 * The supplier-balance move is NOT done here. The caller runs it afterward
 * through [CreditAdjustmentPrompt] with `Source.Purchase`, so it is clamped to
 * what's still owed and asks cash-vs-advance on an overshoot — one adjustment
 * for the whole cancel, not one per product.
 */
object PurchaseCancelRepository {

    private const val EPS = 0.01

    sealed class CancelCheck {
        /** Cancel is allowed; [remainingValue] is the value of goods to sweep back. */
        data class Allowed(val remainingValue: Double) : CancelCheck()
        /** Cancel refused; [reason] is shown to the owner. */
        data class Blocked(val reason: String) : CancelCheck()
        object AlreadyCancelled : CancelCheck()
        object NotFound : CancelCheck()
    }

    /**
     * Read-only test of whether [purchaseId] can be cancelled. Writes nothing.
     */
    suspend fun canCancel(context: Context, purchaseId: Int): CancelCheck =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val purchase = db.purchaseDao().getById(purchaseId)
                ?: return@withContext CancelCheck.NotFound
            if (purchase.isCancelled) return@withContext CancelCheck.AlreadyCancelled

            val items = db.purchaseItemDao().getByPurchase(purchaseId)
            val batchDao = db.purchaseBatchDao()

            var hasConsumption = false
            var remainingValue = 0.0

            for (pid in items.mapNotNull { it.productId }.distinct()) {
                // Asset / service / raw-material lines never get stock or batches, so
                // there is nothing on a shelf to compare: skip them (otherwise held > 0
                // and shelf = 0 would wrongly look like "sold or scrapped").
                if (items.none { it.productId == pid && PurchaseCorrectionRepository.tracksStock(it) }) continue
                val batches = batchDao.getAllBatches(pid)
                    .filter { it.purchaseInvoiceId == purchaseId }

                // Pens of this bill still owned (H) = billed − net taken off by notes.
                // Returns, additions and quantity corrections are all in that net, so
                // they are NOT "consumption". Pens on the shelf (S) are this bill's
                // batch counters. Whatever is owned but not on the shelf (H − S) was
                // sold or scrapped, and that alone blocks a cancel.
                val billed = items.filter { it.productId == pid }.sumOf { it.quantity }
                val held = heldQuantity(billed, db.purchaseReturnDao().getNetQtyOut(purchaseId, pid))
                val onHand = batches.sumOf { it.quantityRemaining }
                if (held - onHand > EPS) hasConsumption = true

                val item = items.find { it.productId == pid }
                val cessPct = item?.cessPercentage ?: db.productDao().getById(pid)?.cessRate ?: 0.0
                val corrected = item?.let { PurchaseCorrectionRepository.correctedRatesOrNull(db, purchaseId, it) }

                remainingValue += batches.sumOf { b ->
                    val taxable = b.unitCostExcludingTax * b.quantityRemaining
                    val tax = taxable * (b.cgstPercent + b.sgstPercent + b.igstPercent) / 100.0
                    val cess = if (corrected != null) {
                        b.quantityRemaining * corrected.cessPerPen
                    } else if (item != null && item.quantity > 0.0 && item.cessAmount > 0.0) {
                        (b.quantityRemaining / item.quantity) * item.cessAmount
                    } else if (cessPct > 0.0) {
                        taxable * cessPct / 100.0
                    } else {
                        0.0
                    }
                    taxable + tax + cess
                }
            }

            if (hasConsumption)
                return@withContext CancelCheck.Blocked(
                    "Some pens from this purchase have already been sold or scrapped, so it cannot be cancelled. Use Debit Notes to return what is left."
                )

            CancelCheck.Allowed(remainingValue)
        }

    /** Result of a completed cancel — the value to run through the balance prompt. */
    data class CancelResult(val remainingValue: Double)

    /**
     * Executes the cancel: sweeps every remaining unit of the purchase back to
     * the supplier (removing stock and reversing ITC via the normal return
     * path) and marks the purchase cancelled. Does NOT touch the supplier
     * balance — the caller does that through the prompt with the returned value.
     *
     * Call [canCancel] first; this trusts that it passed.
     */
    suspend fun cancel(context: Context, purchaseId: Int): CancelResult? =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val purchase = db.purchaseDao().getById(purchaseId) ?: return@withContext null
            if (purchase.isCancelled) return@withContext null

            val items = db.purchaseItemDao().getByPurchase(purchaseId)
            val batchDao = db.purchaseBatchDao()
            val repo = InventoryReductionRepository.get(context)
            val shopId = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getInt("SHOP_ID", -1).takeIf { it > 0 } ?: 1

            var total = 0.0
            for (pid in items.mapNotNull { it.productId }.distinct()) {
                val batches = batchDao.getRemainingBatches(pid)
                    .filter { it.purchaseInvoiceId == purchaseId && it.quantityRemaining > 0.0 }
                if (batches.isEmpty()) continue

                val product = db.productDao().getById(pid) ?: continue
                val lines = batches.map {
                    InventoryReductionRepository.BatchReturnLine(it.id, it.quantityRemaining)
                }

                // isCredit = false so the return path does NOT move the balance;
                // the whole cancel gets one aggregate adjustment via the prompt.
                val res = repo.returnToSupplierByBatches(
                    productId = pid,
                    productName = product.name,
                    variantName = product.variant,
                    hsnCode = product.hsnCode,
                    lines = lines,
                    supplierGstin = purchase.supplierGstin,
                    supplierName = purchase.supplierName,
                    isCredit = false,
                    creditAccountId = null
                )
                total += res?.totalInvoiceValue ?: 0.0
            }

            db.purchaseDao().markPurchaseCancelled(purchaseId, appNow())
            CancelResult(total)
        }
}
