package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.InventoryValuation
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.PurchaseReturn
import com.example.easy_billing.db.ScrapEntry
import com.example.easy_billing.util.GstEngine
import androidx.room.withTransaction

/**
 * Stock-out flows: purchase return, scrap, and "clear remaining
 * stock" (which is just one of the two routed by reason).
 *
 * Quantity for [clearRemainingStock] is intentionally derived from
 * the inventory row — the user does not type it. This matches the
 * spec which says "no manual input" for clear-stock.
 */
class InventoryReductionRepository private constructor(
    private val db: AppDatabase
) {

    enum class ClearReason { PURCHASE_RETURN, SCRAP }

    /**
     * Records a purchase return for an arbitrary quantity.
     * Auto-stamps shop_id + state from the local store_info /
     * gst_profile so the row is push-ready for the backend.
     */
    suspend fun recordPurchaseReturn(entry: PurchaseReturn): Int = db.withTransaction {
        val (shopId, state) = currentShopAndState()
        val toInsert = entry.copy(
            shopId = entry.shopId.ifBlank { shopId },
            state  = entry.state.ifBlank { state }
        )
        val id = db.purchaseReturnDao().insert(toInsert).toInt()
        entry.productId?.let {
            InventoryManager.reduceStock(
                db = db, productId = it, quantity = entry.quantityReturned, type = "RETURN"
            )
        }
        id
    }

    /**
     * Records scrap for an arbitrary quantity.
     */
    suspend fun recordScrap(entry: ScrapEntry): Int = db.withTransaction {
        val (shopId, state) = currentShopAndState()
        val toInsert = entry.copy(
            shopId = entry.shopId.ifBlank { shopId },
            state  = entry.state.ifBlank { state }
        )
        val id = db.scrapDao().insert(toInsert).toInt()
        entry.productId?.let {
            InventoryManager.reduceStock(
                db = db, productId = it, quantity = entry.quantity, type = "LOSS"
            )
        }
        id
    }

    /** Resolve shop_id (gstin) + state (gst_profile.stateCode → name). */
    private suspend fun currentShopAndState(): Pair<String, String> {
        val store = db.storeInfoDao().get()
        val gst   = db.gstProfileDao().get()
        val shopId = gst?.shopId?.takeIf { it.isNotBlank() }
            ?: store?.gstin.orEmpty()
        val rawStateCode = gst?.stateCode?.takeIf { it.isNotBlank() }
            ?: store?.gstin
        val stateCode = GstEngine.getStateCode(rawStateCode)
        val stateName = GstEngine.INDIA_STATES[stateCode] ?: stateCode
        return shopId to stateName
    }

    /**
     * Reduces stock for [productId] by a specific [quantity], routing
     * it to either `purchase_return_table` or `scrap_table`. The
     * taxable amount and taxes are derived from the current
     * inventory's average cost.
     */
    suspend fun reduceStockByReason(
        productId: Int,
        productName: String,
        variantName: String?,
        hsnCode: String?,
        quantity: Double,
        reason: ClearReason,
        purchaseTaxCgst: Double = 0.0,
        purchaseTaxSgst: Double = 0.0,
        purchaseTaxIgst: Double = 0.0,
        isCredit: Boolean = false,
        creditAccountId: Int? = null,
        shopId: Int = 1
    ): Boolean = db.withTransaction {

        val inventory = db.inventoryDao().getInventory(productId)
            ?: return@withTransaction false

        val available = inventory.currentStock
        if (available < quantity) return@withTransaction false

        val avgCost = inventory.averageCost
        val taxableAmount = quantity * avgCost

        val store = db.storeInfoDao().get()
        val gst   = db.gstProfileDao().get()
        val rawShopStateCode = gst?.stateCode?.takeIf { it.isNotBlank() }
            ?: store?.gstin
        val shopStateCode = GstEngine.getStateCode(rawShopStateCode)

        val sameState = if (shopStateCode.isNotBlank()) {
            purchaseTaxIgst <= 0.0
        } else {
            true
        }

        val cgstAmt = if (sameState) taxableAmount * purchaseTaxCgst / 100.0 else 0.0
        val sgstAmt = if (sameState) taxableAmount * purchaseTaxSgst / 100.0 else 0.0
        val igstAmt = if (!sameState) taxableAmount * purchaseTaxIgst / 100.0 else 0.0
        val invoiceValue = if (sameState) {
            taxableAmount + cgstAmt + sgstAmt
        } else {
            taxableAmount + igstAmt
        }

        val (shopIdStr, stateStr) = currentShopAndState()

        when (reason) {
            ClearReason.PURCHASE_RETURN -> db.purchaseReturnDao().insert(
                PurchaseReturn(
                    shopId           = shopIdStr,
                    productId        = productId,
                    productName      = productName,
                    variantName      = variantName,
                    hsnCode          = hsnCode,
                    quantityReturned = quantity,
                    taxableAmount    = taxableAmount,
                    invoiceValue     = invoiceValue,
                    cgstPercentage   = if (sameState) purchaseTaxCgst else 0.0,
                    sgstPercentage   = if (sameState) purchaseTaxSgst else 0.0,
                    igstPercentage   = if (!sameState) purchaseTaxIgst else 0.0,
                    cgstAmount       = cgstAmt,
                    sgstAmount       = sgstAmt,
                    igstAmount       = igstAmt,
                    state            = stateStr,
                    isCredit         = isCredit,
                    creditAccountId  = creditAccountId
                )
            )
            ClearReason.SCRAP -> db.scrapDao().insert(
                ScrapEntry(
                    shopId         = shopIdStr,
                    productId      = productId,
                    productName    = productName,
                    variantName    = variantName,
                    hsnCode        = hsnCode,
                    quantity       = quantity,
                    taxableAmount  = taxableAmount,
                    invoiceValue   = invoiceValue,
                    cgstPercentage = if (sameState) purchaseTaxCgst else 0.0,
                    sgstPercentage = if (sameState) purchaseTaxSgst else 0.0,
                    igstPercentage = if (!sameState) purchaseTaxIgst else 0.0,
                    cgstAmount     = cgstAmt,
                    sgstAmount     = sgstAmt,
                    igstAmount     = igstAmt,
                    state          = stateStr,
                    reason         = "Manual reduction"
                )
            )
        }

        // Credit Adjustment
        if (reason == ClearReason.PURCHASE_RETURN && isCredit && creditAccountId != null) {
            val account = db.creditAccountDao().getById(creditAccountId, shopId)
            if (account != null) {
                // Reduce debt
                val newDue = account.dueAmount - invoiceValue
                db.creditAccountDao().updateDue(account.id, newDue, shopId)

                // Log transaction
                db.creditTransactionDao().insert(
                    com.example.easy_billing.db.CreditTransaction(
                        accountId = account.id,
                        shopId = shopId,
                        amount = -invoiceValue, // Negative for return
                        type = "PURCHASE_RETURN",
                        referenceInvoice = "RETURN_$productName",
                        isSynced = false
                    )
                )
            }
        }

        InventoryManager.reduceStock(
            db = db, productId = productId, quantity = quantity,
            type = if (reason == ClearReason.PURCHASE_RETURN) "RETURN" else "LOSS"
        )
        true
    }

    /**
     * Clears the entire remaining stock for [productId], routing
     * the entry to either `purchase_return_table` or `scrap_table`
     * based on [reason]. The quantity is whatever's currently in
     * inventory — the caller does not pass one.
     */
    suspend fun clearRemainingStock(
        productId: Int,
        productName: String,
        variantName: String?,
        hsnCode: String?,
        reason: ClearReason,
        purchaseTaxCgst: Double = 0.0,
        purchaseTaxSgst: Double = 0.0,
        purchaseTaxIgst: Double = 0.0,
        supplierGstin: String? = null,
        supplierName: String? = null,
        isCredit: Boolean = false,
        creditAccountId: Int? = null,
        shopId: Int = 1
    ): ClearStockResult = db.withTransaction {

        val inventory = db.inventoryDao().getInventory(productId)
            ?: return@withTransaction ClearStockResult.NoStock

        val qty = inventory.currentStock
        if (qty <= 0) return@withTransaction ClearStockResult.NoStock

        val avgCost = inventory.averageCost
        val taxableAmount = qty * avgCost

        val store = db.storeInfoDao().get()
        val gst   = db.gstProfileDao().get()
        val rawShopStateCode = gst?.stateCode?.takeIf { it.isNotBlank() }
            ?: store?.gstin
        val shopStateCode = GstEngine.getStateCode(rawShopStateCode)

        val supplierStateCode = GstEngine.getStateCode(supplierGstin)
        val sameState = if (shopStateCode.isNotBlank() && supplierStateCode.isNotBlank()) {
            shopStateCode == supplierStateCode
        } else {
            purchaseTaxIgst <= 0.0
        }

        val cgstAmt = if (sameState) taxableAmount * purchaseTaxCgst / 100.0 else 0.0
        val sgstAmt = if (sameState) taxableAmount * purchaseTaxSgst / 100.0 else 0.0
        val igstAmt = if (!sameState) taxableAmount * purchaseTaxIgst / 100.0 else 0.0
        val invoiceValue = if (sameState) {
            taxableAmount + cgstAmt + sgstAmt
        } else {
            taxableAmount + igstAmt
        }

        val (shopIdStr, stateStr) = currentShopAndState()

        when (reason) {
            ClearReason.PURCHASE_RETURN -> db.purchaseReturnDao().insert(
                PurchaseReturn(
                    shopId           = shopIdStr,
                    productId        = productId,
                    productName      = productName,
                    variantName      = variantName,
                    hsnCode          = hsnCode,
                    quantityReturned = qty,
                    taxableAmount    = taxableAmount,
                    invoiceValue     = invoiceValue,
                    cgstPercentage   = if (sameState) purchaseTaxCgst else 0.0,
                    sgstPercentage   = if (sameState) purchaseTaxSgst else 0.0,
                    igstPercentage   = if (!sameState) purchaseTaxIgst else 0.0,
                    cgstAmount       = cgstAmt,
                    sgstAmount       = sgstAmt,
                    igstAmount       = igstAmt,
                    state            = stateStr,
                    supplierGstin    = supplierGstin,
                    supplierName     = supplierName,
                    isCredit         = isCredit,
                    creditAccountId  = creditAccountId
                )
            )
            ClearReason.SCRAP -> db.scrapDao().insert(
                ScrapEntry(
                    shopId         = shopIdStr,
                    productId      = productId,
                    productName    = productName,
                    variantName    = variantName,
                    hsnCode        = hsnCode,
                    quantity       = qty,
                    taxableAmount  = taxableAmount,
                    invoiceValue   = invoiceValue,
                    cgstPercentage = if (sameState) purchaseTaxCgst else 0.0,
                    sgstPercentage = if (sameState) purchaseTaxSgst else 0.0,
                    igstPercentage = if (!sameState) purchaseTaxIgst else 0.0,
                    cgstAmount     = cgstAmt,
                    sgstAmount     = sgstAmt,
                    igstAmount     = igstAmt,
                    state          = stateStr,
                    reason         = "Stock cleared"
                )
            )
        }

        // Credit Adjustment
        if (reason == ClearReason.PURCHASE_RETURN && isCredit && creditAccountId != null) {
            val account = db.creditAccountDao().getById(creditAccountId, shopId)
            if (account != null) {
                val newDue = account.dueAmount - invoiceValue
                db.creditAccountDao().updateDue(account.id, newDue, shopId)

                db.creditTransactionDao().insert(
                    com.example.easy_billing.db.CreditTransaction(
                        accountId = account.id,
                        shopId = shopId,
                        amount = -invoiceValue,
                        type = "PURCHASE_RETURN",
                        referenceInvoice = "CLEAR_$productName",
                        isSynced = false
                    )
                )
            }
        }

        // Inventory → 0 via the existing InventoryManager.clearStock
        // path so transaction logs stay consistent.
        InventoryManager.clearStock(
            db = db, productId = productId,
            type = if (reason == ClearReason.PURCHASE_RETURN) "RETURN" else "LOSS"
        )

        ClearStockResult.Cleared(qty, reason)
    }

    sealed class ClearStockResult {
        object NoStock : ClearStockResult()
        data class Cleared(val quantity: Double, val reason: ClearReason) : ClearStockResult()
    }

    /**
     * One row from the batch-picker UI for a supplier return — the
     * id of the source [com.example.easy_billing.db.PurchaseBatch] +
     * how many units of *that* batch the user is sending back.
     */
    data class BatchReturnLine(
        val batchId: Int,
        val quantity: Double
    )

    /**
     * Result of [returnToSupplierByBatches]. Includes a per-batch
     * value breakdown for the host UI to render.
     */
    data class BatchReturnResult(
        val returnId: Int,
        val totalQuantity: Double,
        val totalTaxable: Double,
        val totalInvoiceValue: Double,
        val totalCgst: Double,
        val totalSgst: Double,
        val totalIgst: Double
    )

    data class BatchScrapLine(
        val batchId: Int,
        val quantity: Double
    )

    data class BatchScrapResult(
        val scrapId: Int,
        val totalQuantity: Double,
        val totalTaxable: Double,
        val totalInvoiceValue: Double,
        val totalCgst: Double,
        val totalSgst: Double,
        val totalIgst: Double
    )

    /**
     * Supplier-return flow with batch precision.
     *
     * Differs from [reduceStockByReason]+[clearRemainingStock] in
     * three ways:
     *
     *   • Value is computed per-batch at the batch's own unit cost
     *     and GST split — NOT the current weighted average. This is
     *     what fixes the inconsistency the architecture spec calls
     *     out (returning Batch 2 @ ₹20 must value at ₹20, not the
     *     weighted-avg ₹15).
     *   • The exact batches the user picked are debited via
     *     [InventoryValuation.reduceBatches] — no FIFO walking.
     *   • [InventoryManager.reduceStock] is invoked with
     *     `skipBatchConsume = true` so the inventory row is reduced
     *     without a second pass over the batch table.
     *
     * Returns null if the request is malformed (empty selection,
     * batch belongs to a different product, etc.). On success
     * returns a [BatchReturnResult] with the aggregate totals.
     */
    suspend fun returnToSupplierByBatches(
        productId: Int,
        productName: String,
        variantName: String?,
        hsnCode: String?,
        lines: List<BatchReturnLine>,
        supplierGstin: String? = null,
        supplierName: String? = null,
        isCredit: Boolean = false,
        creditAccountId: Int? = null,
        shopId: Int = 1
    ): BatchReturnResult? = db.withTransaction {

        if (lines.isEmpty()) return@withTransaction null

        // Resolve every batch up-front so we can validate before we
        // start mutating anything.
        val batchDao = db.purchaseBatchDao()
        val resolved = lines.map { line ->
            val b = batchDao.getBatchById(line.batchId)
                ?: return@withTransaction null
            if (b.productId != productId) return@withTransaction null
            if (line.quantity <= 0.0 || line.quantity > b.quantityRemaining) {
                return@withTransaction null
            }
            b to line.quantity
        }

        val store = db.storeInfoDao().get()
        val gst   = db.gstProfileDao().get()
        val rawShopStateCode = gst?.stateCode?.takeIf { it.isNotBlank() }
            ?: store?.gstin
        val shopStateCode = GstEngine.getStateCode(rawShopStateCode)
        val (shopIdStr, shopStateName) = currentShopAndState()
        val product = db.productDao().getById(productId)

        var grandReturnId: Int = 0
        var grandTotalQuantity: Double = 0.0
        var grandTotalTaxable: Double = 0.0
        var grandTotalInvoiceValue: Double = 0.0
        var grandTotalCgst: Double = 0.0
        var grandTotalSgst: Double = 0.0
        var grandTotalIgst: Double = 0.0

        // Create separate entries for each batch individually
        resolved.forEach { (batch, qty) ->
            val parentPurchase = batch.purchaseInvoiceId?.let { db.purchaseDao().getById(it) }
            val sameStateForThisBatch: Boolean
            val batchStateName: String

            if (parentPurchase != null && parentPurchase.state.isNotBlank()) {
                batchStateName = parentPurchase.state.trim()
                sameStateForThisBatch = batchStateName.lowercase() == shopStateName.trim().lowercase()
            } else {
                val supplierStateCode = GstEngine.getStateCode(batch.supplierGstin)
                if (shopStateCode.isNotBlank() && supplierStateCode.isNotBlank()) {
                    sameStateForThisBatch = shopStateCode == supplierStateCode
                    batchStateName = GstEngine.INDIA_STATES[supplierStateCode] ?: shopStateName
                } else {
                    // Fallback based on batch or product IGST
                    val igstPct = if (batch.cgstPercent == 0.0 && batch.sgstPercent == 0.0 && batch.igstPercent == 0.0 && product != null) {
                        product.igstPercentage
                    } else {
                        batch.igstPercent
                    }
                    sameStateForThisBatch = igstPct <= 0.0
                    batchStateName = if (sameStateForThisBatch) shopStateName else "Other State"
                }
            }

            val taxable = qty * batch.unitCostExcludingTax

            val cgstPct = if (batch.cgstPercent == 0.0 && batch.sgstPercent == 0.0 && batch.igstPercent == 0.0 && product != null) {
                product.cgstPercentage
            } else {
                batch.cgstPercent
            }
            val sgstPct = if (batch.cgstPercent == 0.0 && batch.sgstPercent == 0.0 && batch.igstPercent == 0.0 && product != null) {
                product.sgstPercentage
            } else {
                batch.sgstPercent
            }
            val igstPct = if (batch.cgstPercent == 0.0 && batch.sgstPercent == 0.0 && batch.igstPercent == 0.0 && product != null) {
                product.igstPercentage
            } else {
                batch.igstPercent
            }

            val cgst = if (sameStateForThisBatch) taxable * cgstPct / 100.0 else 0.0
            val sgst = if (sameStateForThisBatch) taxable * sgstPct / 100.0 else 0.0
            val igst = if (!sameStateForThisBatch) taxable * igstPct / 100.0 else 0.0
            val invoice = if (sameStateForThisBatch) {
                taxable + cgst + sgst
            } else {
                taxable + igst
            }

            // Round to 2 decimal places
            val roundedTaxable = Math.round(taxable * 100.0) / 100.0
            val roundedInvoice = Math.round(invoice * 100.0) / 100.0
            val roundedCgst = Math.round(cgst * 100.0) / 100.0
            val roundedSgst = Math.round(sgst * 100.0) / 100.0
            val roundedIgst = Math.round(igst * 100.0) / 100.0

            val batchCgstPctRounded = Math.round(cgstPct * 100.0) / 100.0
            val batchSgstPctRounded = Math.round(sgstPct * 100.0) / 100.0
            val batchIgstPctRounded = Math.round(igstPct * 100.0) / 100.0

            val batchSupplierGstin = batch.supplierGstin?.takeIf { it.isNotBlank() } ?: supplierGstin
            val batchSupplierName = batch.supplierName?.takeIf { it.isNotBlank() } ?: supplierName

            val returnId = db.purchaseReturnDao().insert(
                PurchaseReturn(
                    shopId           = shopIdStr,
                    productId        = productId,
                    productName      = productName,
                    variantName      = variantName,
                    hsnCode          = hsnCode,
                    quantityReturned = qty,
                    taxableAmount    = roundedTaxable,
                    invoiceValue     = roundedInvoice,
                    cgstPercentage   = batchCgstPctRounded,
                    sgstPercentage   = batchSgstPctRounded,
                    igstPercentage   = batchIgstPctRounded,
                    cgstAmount       = roundedCgst,
                    sgstAmount       = roundedSgst,
                    igstAmount       = roundedIgst,
                    state            = batchStateName,
                    supplierGstin    = batchSupplierGstin,
                    supplierName     = batchSupplierName,
                    isCredit         = isCredit,
                    creditAccountId  = creditAccountId
                )
            ).toInt()

            grandReturnId = returnId
            grandTotalQuantity += qty
            grandTotalTaxable += roundedTaxable
            grandTotalInvoiceValue += roundedInvoice
            grandTotalCgst += roundedCgst
            grandTotalSgst += roundedSgst
            grandTotalIgst += roundedIgst
        }

        // Inventory row + log + transaction. skipBatchConsume = true
        // because we will do the per-batch debit below.
        InventoryManager.reduceStock(
            db = db,
            productId = productId,
            quantity = grandTotalQuantity,
            type = "RETURN",
            skipBatchConsume = true
        )

        // Debit the specific batches the user picked. This walks each
        // line via PurchaseBatchDao.reduceBatchQuantity which refuses
        // to drive a batch negative.
        InventoryValuation.reduceBatches(
            db = db,
            productId = productId,
            lines = resolved.map { (b, qty) ->
                InventoryValuation.BatchReduction(batchId = b.id, quantity = qty)
            }
        )

        // Optional credit adjustment.
        if (isCredit && creditAccountId != null) {
            val account = db.creditAccountDao().getById(creditAccountId, shopId)
            if (account != null) {
                val newDue = account.dueAmount - grandTotalInvoiceValue
                db.creditAccountDao().updateDue(account.id, newDue, shopId)
                db.creditTransactionDao().insert(
                    com.example.easy_billing.db.CreditTransaction(
                        accountId = account.id,
                        shopId = shopId,
                        amount = -grandTotalInvoiceValue,
                        type = "PURCHASE_RETURN",
                        referenceInvoice = "BATCH_RETURN_$productName",
                        isSynced = false
                    )
                )
            }
        }

        BatchReturnResult(
            returnId = grandReturnId,
            totalQuantity = grandTotalQuantity,
            totalTaxable = grandTotalTaxable,
            totalInvoiceValue = grandTotalInvoiceValue,
            totalCgst = grandTotalCgst,
            totalSgst = grandTotalSgst,
            totalIgst = grandTotalIgst
        )
    }

    suspend fun scrapByBatches(
        productId: Int,
        productName: String,
        variantName: String?,
        hsnCode: String?,
        lines: List<BatchScrapLine>,
        shopId: Int = 1
    ): BatchScrapResult? = db.withTransaction {

        if (lines.isEmpty()) return@withTransaction null

        // Resolve every batch up-front so we can validate before we
        // start mutating anything.
        val batchDao = db.purchaseBatchDao()
        val resolved = lines.map { line ->
            val b = batchDao.getBatchById(line.batchId)
                ?: return@withTransaction null
            if (b.productId != productId) return@withTransaction null
            if (line.quantity <= 0.0 || line.quantity > b.quantityRemaining) {
                return@withTransaction null
            }
            b to line.quantity
        }

        val store = db.storeInfoDao().get()
        val gst   = db.gstProfileDao().get()
        val rawShopStateCode = gst?.stateCode?.takeIf { it.isNotBlank() }
            ?: store?.gstin
        val shopStateCode = GstEngine.getStateCode(rawShopStateCode)
        val (shopIdStr, shopStateName) = currentShopAndState()
        val product = db.productDao().getById(productId)

        var grandScrapId: Int = 0
        var grandTotalQuantity: Double = 0.0
        var grandTotalTaxable: Double = 0.0
        var grandTotalInvoiceValue: Double = 0.0
        var grandTotalCgst: Double = 0.0
        var grandTotalSgst: Double = 0.0
        var grandTotalIgst: Double = 0.0

        // Create separate entries for each batch individually
        resolved.forEach { (batch, qty) ->
            val parentPurchase = batch.purchaseInvoiceId?.let { db.purchaseDao().getById(it) }
            val sameStateForThisBatch: Boolean
            val batchStateName: String

            if (parentPurchase != null && parentPurchase.state.isNotBlank()) {
                batchStateName = parentPurchase.state.trim()
                sameStateForThisBatch = batchStateName.lowercase() == shopStateName.trim().lowercase()
            } else {
                val supplierStateCode = GstEngine.getStateCode(batch.supplierGstin)
                if (shopStateCode.isNotBlank() && supplierStateCode.isNotBlank()) {
                    sameStateForThisBatch = shopStateCode == supplierStateCode
                    batchStateName = GstEngine.INDIA_STATES[supplierStateCode] ?: shopStateName
                } else {
                    // Fallback based on batch or product IGST
                    val igstPct = if (batch.cgstPercent == 0.0 && batch.sgstPercent == 0.0 && batch.igstPercent == 0.0 && product != null) {
                        product.igstPercentage
                    } else {
                        batch.igstPercent
                    }
                    sameStateForThisBatch = igstPct <= 0.0
                    batchStateName = if (sameStateForThisBatch) shopStateName else "Other State"
                }
            }

            val taxable = qty * batch.unitCostExcludingTax

            val cgstPct = if (batch.cgstPercent == 0.0 && batch.sgstPercent == 0.0 && batch.igstPercent == 0.0 && product != null) {
                product.cgstPercentage
            } else {
                batch.cgstPercent
            }
            val sgstPct = if (batch.cgstPercent == 0.0 && batch.sgstPercent == 0.0 && batch.igstPercent == 0.0 && product != null) {
                product.sgstPercentage
            } else {
                batch.sgstPercent
            }
            val igstPct = if (batch.cgstPercent == 0.0 && batch.sgstPercent == 0.0 && batch.igstPercent == 0.0 && product != null) {
                product.igstPercentage
            } else {
                batch.igstPercent
            }

            val cgst = if (sameStateForThisBatch) taxable * cgstPct / 100.0 else 0.0
            val sgst = if (sameStateForThisBatch) taxable * sgstPct / 100.0 else 0.0
            val igst = if (!sameStateForThisBatch) taxable * igstPct / 100.0 else 0.0
            val invoice = if (sameStateForThisBatch) {
                taxable + cgst + sgst
            } else {
                taxable + igst
            }

            // Round to 2 decimal places
            val roundedTaxable = Math.round(taxable * 100.0) / 100.0
            val roundedInvoice = Math.round(invoice * 100.0) / 100.0
            val roundedCgst = Math.round(cgst * 100.0) / 100.0
            val roundedSgst = Math.round(sgst * 100.0) / 100.0
            val roundedIgst = Math.round(igst * 100.0) / 100.0

            val batchCgstPctRounded = Math.round(cgstPct * 100.0) / 100.0
            val batchSgstPctRounded = Math.round(sgstPct * 100.0) / 100.0
            val batchIgstPctRounded = Math.round(igstPct * 100.0) / 100.0

            val scrapId = db.scrapDao().insert(
                ScrapEntry(
                    shopId         = shopIdStr,
                    productId      = productId,
                    productName    = productName,
                    variantName    = variantName,
                    hsnCode        = hsnCode,
                    quantity       = qty,
                    taxableAmount  = roundedTaxable,
                    invoiceValue   = roundedInvoice,
                    cgstPercentage   = batchCgstPctRounded,
                    sgstPercentage   = batchSgstPctRounded,
                    igstPercentage   = batchIgstPctRounded,
                    cgstAmount       = roundedCgst,
                    sgstAmount       = roundedSgst,
                    igstAmount       = roundedIgst,
                    state            = batchStateName,
                    reason         = "Scrap"
                )
            ).toInt()

            grandScrapId = scrapId
            grandTotalQuantity += qty
            grandTotalTaxable += roundedTaxable
            grandTotalInvoiceValue += roundedInvoice
            grandTotalCgst += roundedCgst
            grandTotalSgst += roundedSgst
            grandTotalIgst += roundedIgst
        }

        // Inventory row + log + transaction. skipBatchConsume = true
        // because we will do the per-batch debit below.
        InventoryManager.reduceStock(
            db = db,
            productId = productId,
            quantity = grandTotalQuantity,
            type = "LOSS",
            skipBatchConsume = true
        )

        // Debit the specific batches the user picked.
        InventoryValuation.reduceBatches(
            db = db,
            productId = productId,
            lines = resolved.map { (b, qty) ->
                InventoryValuation.BatchReduction(batchId = b.id, quantity = qty)
            }
        )

        BatchScrapResult(
            scrapId = grandScrapId,
            totalQuantity = grandTotalQuantity,
            totalTaxable = grandTotalTaxable,
            totalInvoiceValue = grandTotalInvoiceValue,
            totalCgst = grandTotalCgst,
            totalSgst = grandTotalSgst,
            totalIgst = grandTotalIgst
        )
    }

    /** Convenience for the batch-picker dialog — what's still on the shelf. */
    suspend fun getRemainingBatchesForProduct(productId: Int) =
        db.purchaseBatchDao().getRemainingBatches(productId)

    companion object {
        @Volatile private var INSTANCE: InventoryReductionRepository? = null

        fun get(context: Context): InventoryReductionRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: InventoryReductionRepository(
                    AppDatabase.getDatabase(context)
                ).also { INSTANCE = it }
            }
        }
    }
}
