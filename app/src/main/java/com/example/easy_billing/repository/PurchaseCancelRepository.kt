package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.db.AppDatabase
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
 * been sold.** You can't return goods you've already sold on. Partial prior
 * returns are fine — cancel just sweeps up whatever remains. "Sold" is measured
 * as consumption of this purchase's batches that isn't explained by returns
 * booked against the purchase; if that's non-zero, we block rather than risk
 * corrupting stock.
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

    private fun fmt(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)

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
            val returnDao = db.purchaseReturnDao()

            var soldUnits = 0.0
            var remainingValue = 0.0

            for (pid in items.mapNotNull { it.productId }.distinct()) {
                val batches = batchDao.getAllBatches(pid)
                    .filter { it.purchaseInvoiceId == purchaseId }

                val purchased = batches.sumOf { it.quantityPurchased }
                val onHand = batches.sumOf { it.quantityRemaining }
                val consumed = purchased - onHand

                // Net returned booked against this purchase (D minus C). Only the
                // part of consumption it explains is safe; the rest looks sold.
                val returned = returnDao
                    .getTotalReturnedForInvoiceProduct(purchaseId, pid)
                    .coerceAtLeast(0.0)

                val unexplained = consumed - returned
                if (unexplained > EPS) soldUnits += unexplained

                remainingValue += batches.sumOf { b ->
                    val taxable = b.unitCostExcludingTax * b.quantityRemaining
                    val tax = taxable * (b.cgstPercent + b.sgstPercent + b.igstPercent) / 100.0
                    taxable + tax
                }
            }

            if (soldUnits > EPS)
                return@withContext CancelCheck.Blocked(
                    "${fmt(soldUnits)} unit(s) from this purchase are already sold, so it " +
                        "can't be cancelled. Use a debit note / return for what's still in stock."
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
                    creditAccountId = null,
                    shopId = shopId
                )
                total += res?.totalInvoiceValue ?: 0.0
            }

            db.purchaseDao().markPurchaseCancelled(purchaseId, appNow())
            CancelResult(total)
        }
}
