package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.InventoryValuation
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.PurchaseReturn
import com.example.easy_billing.db.ScrapEntry
import com.example.easy_billing.util.GstEngine
import com.example.easy_billing.util.appNow
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

    // NOTE (Phase 6 cleanup): a `recordPurchaseReturn(entry: PurchaseReturn)`
    // function used to live here. It had zero call sites anywhere in the app
    // (dead code) and, worse, was not safe to resurrect: it inserted a bare
    // PurchaseReturn row with none of the GST/debit-note tagging fields
    // (note_type, original_invoice_id, place_of_supply, etc.) that
    // returnToSupplierByBatches() below stamps on every row, and it removed
    // stock via the generic InventoryManager.reduceStock() instead of the
    // real per-batch FIFO consumption the return flow depends on for correct
    // variance and supplier attribution. Deleted rather than fixed in place,
    // since returnToSupplierByBatches() already covers this need correctly.

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
                db = db, productId = it, quantity = entry.quantity, type = InventoryManager.LogType.LOSS
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
        creditAccountId: Int? = null
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

        var returnRowId = -1
        // Purchase-return-flow audit, Phase 2: sum of the invoice value
        // across every row this call inserts, for the credit-adjustment
        // amount handed back below. Only meaningful for PURCHASE_RETURN;
        // stays 0.0 for SCRAP (which has no supplier balance to adjust).
        var totalCreditableInvoiceValue = 0.0

        when (reason) {
            ClearReason.PURCHASE_RETURN -> {
                // Purchase-return-flow audit, Phase 2: this function used
                // to insert ONE row for the whole cleared quantity, valued
                // at the current average and attributed to whatever
                // supplier the caller happened to pass in — with zero
                // gain/loss ever computed, since it had no per-batch
                // knowledge to compute one from. Now it delegates to
                // [returnToSupplierByBatches], the same per-batch-precise
                // function the Inventory batch-picker uses, feeding it
                // EVERY remaining batch for this product. That gives each
                // row its own correct supplier, its own correct GST rates,
                // a real gain/loss vs. that batch's own original cost, and
                // (via Phase 1) proper note/GSTR-2 tagging — all through
                // one shared implementation instead of a second, simpler
                // copy of the same logic.
                val remainingBatches = db.purchaseBatchDao().getRemainingBatches(productId)

                if (remainingBatches.isEmpty()) {
                    // Legacy/edge case: a product with stock but no batch
                    // ledger at all (pre-dates the batch system and was
                    // never back-filled). No per-batch precision is
                    // possible — fall back to the old aggregate row so
                    // this still clears the stock and creates SOME record,
                    // rather than silently doing nothing.
                    returnRowId = db.purchaseReturnDao().insert(
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
                            creditAccountId  = creditAccountId,
                            noteNumber             = "DN-%05d".format(db.purchaseReturnDao().getMaxDebitNoteSequence() + 1),
                            noteDate               = appNow(),
                            noteType               = "D",
                            placeOfSupply          = GstEngine.INDIA_STATES[shopStateCode] ?: shopStateCode,
                            placeOfSupplyCode      = shopStateCode,
                            supplyType             = if (sameState) "intrastate" else "interstate",
                            documentType           = "Debit Note",
                            documentNature         = "Debit Note",
                            documentSeries         = "DN",
                            preGst                 = "N",
                            reasonForIssuingDocument = "Purchase return",
                            noteRefundVoucherValue = Math.round(invoiceValue * 100.0) / 100.0,
                            rate                   = 0.0,
                            eligibilityForItc      = "Inputs",
                            availedItcIntegratedTax = 0.0,
                            availedItcCentralTax   = 0.0,
                            availedItcStateTax     = 0.0,
                            availedItcCess         = 0.0,
                            invoiceType            = "Regular"
                            // inventoryValuationVariance stays 0.0 — there's
                            // no batch to compare the current average against.
                        )
                    ).toInt()
                    totalCreditableInvoiceValue = invoiceValue

                    InventoryManager.clearStock(
                        db = db, productId = productId,
                        type = InventoryManager.LogType.PURCHASE_RETURN
                    )
                } else {
                    val batchLines = remainingBatches.map {
                        BatchReturnLine(batchId = it.id, quantity = it.quantityRemaining)
                    }
                    // isCredit/creditAccountId = false/null here: this call
                    // builds its OWN credit-adjustment info below (summed
                    // across every row it inserts, plus the leftover row
                    // if drift leaves anything uncleared), rather than
                    // letting returnToSupplierByBatches build a separate
                    // one scoped to just the batch-covered portion.
                    val batchResult = returnToSupplierByBatches(
                        productId       = productId,
                        productName     = productName,
                        variantName     = variantName,
                        hsnCode         = hsnCode,
                        lines           = batchLines,
                        supplierGstin   = supplierGstin,
                        supplierName    = supplierName,
                        isCredit        = false,
                        creditAccountId = null
                    )
                    returnRowId = batchResult?.returnId ?: -1
                    totalCreditableInvoiceValue = batchResult?.totalInvoiceValue ?: 0.0

                    // Drift safety net: the batch ledger is SUPPOSED to sum
                    // to inventory.currentStock, but if something has left
                    // it briefly out of sync, re-check what's actually left
                    // after the batch-precise pass above and sweep up any
                    // remainder the same way the old code always did for
                    // 100% of the quantity — untraceable to one invoice, so
                    // valued at the current average with zero variance —
                    // rather than leaving stock behind and breaking this
                    // function's "clears everything" contract.
                    val remainingAfterBatches = db.inventoryDao().getInventory(productId)?.currentStock ?: 0.0
                    if (remainingAfterBatches > 0.0001) {
                        val leftoverAvg = db.inventoryDao().getInventory(productId)?.averageCost ?: 0.0
                        val leftoverTaxable = remainingAfterBatches * leftoverAvg
                        val leftoverCgst = if (sameState) leftoverTaxable * purchaseTaxCgst / 100.0 else 0.0
                        val leftoverSgst = if (sameState) leftoverTaxable * purchaseTaxSgst / 100.0 else 0.0
                        val leftoverIgst = if (!sameState) leftoverTaxable * purchaseTaxIgst / 100.0 else 0.0
                        val leftoverInvoice = if (sameState) {
                            leftoverTaxable + leftoverCgst + leftoverSgst
                        } else {
                            leftoverTaxable + leftoverIgst
                        }

                        returnRowId = db.purchaseReturnDao().insert(
                            PurchaseReturn(
                                shopId           = shopIdStr,
                                productId        = productId,
                                productName      = productName,
                                variantName      = variantName,
                                hsnCode          = hsnCode,
                                quantityReturned = remainingAfterBatches,
                                taxableAmount    = Math.round(leftoverTaxable * 100.0) / 100.0,
                                invoiceValue     = Math.round(leftoverInvoice * 100.0) / 100.0,
                                cgstPercentage   = if (sameState) purchaseTaxCgst else 0.0,
                                sgstPercentage   = if (sameState) purchaseTaxSgst else 0.0,
                                igstPercentage   = if (!sameState) purchaseTaxIgst else 0.0,
                                cgstAmount       = Math.round(leftoverCgst * 100.0) / 100.0,
                                sgstAmount       = Math.round(leftoverSgst * 100.0) / 100.0,
                                igstAmount       = Math.round(leftoverIgst * 100.0) / 100.0,
                                state            = stateStr,
                                supplierGstin    = supplierGstin,
                                supplierName     = supplierName,
                                isCredit         = isCredit,
                                creditAccountId  = creditAccountId,
                                noteNumber             = "DN-%05d".format(db.purchaseReturnDao().getMaxDebitNoteSequence() + 1),
                                noteDate               = appNow(),
                                noteType               = "D",
                                placeOfSupply          = GstEngine.INDIA_STATES[shopStateCode] ?: shopStateCode,
                                placeOfSupplyCode      = shopStateCode,
                                supplyType             = if (sameState) "intrastate" else "interstate",
                                documentType           = "Debit Note",
                                documentNature         = "Debit Note",
                                documentSeries         = "DN",
                                preGst                 = "N",
                                reasonForIssuingDocument = "Purchase return (untraceable balance)",
                                noteRefundVoucherValue = Math.round(leftoverInvoice * 100.0) / 100.0,
                                rate                   = 0.0,
                                eligibilityForItc      = "Inputs",
                                availedItcIntegratedTax = 0.0,
                                availedItcCentralTax   = 0.0,
                                availedItcStateTax     = 0.0,
                                availedItcCess         = 0.0,
                                invoiceType            = "Regular"
                                // No originalInvoiceId/variance — this
                                // slice isn't traceable to one specific
                                // batch, so there's nothing to compare
                                // against.
                            )
                        ).toInt()
                        totalCreditableInvoiceValue += leftoverInvoice

                        InventoryManager.clearStock(
                            db = db, productId = productId,
                            type = InventoryManager.LogType.PURCHASE_RETURN
                        )
                    }
                }
            }
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

        // Supplier-balance adjustment lifted OUT — see the note in
        // PurchaseReturnViewModel. The caller runs it through
        // CreditAdjustmentPrompt after this returns, so it is clamped to the
        // account balance and asks cash-vs-advance on an overshoot. The values
        // needed for that are handed back on ClearStockResult.Cleared.

        // Purchase-return-flow audit, Phase 2: for PURCHASE_RETURN, stock
        // is already fully cleared by this point — either by
        // returnToSupplierByBatches's own reduceStock call (the normal
        // per-batch path), or by the explicit InventoryManager.clearStock
        // calls inside the no-batches/leftover branches above. Calling
        // clearStock a SECOND time here unconditionally, like the old
        // code did, would be a double-clear (harmless once stock is
        // already 0, but wasteful and misleading in the log history —
        // an extra PURCHASE_RETURN-type log entry for zero-effect work).
        // SCRAP never goes through the branch above, so it still needs
        // this call exactly as before.
        if (reason == ClearReason.SCRAP) {
            InventoryManager.clearStock(
                db = db, productId = productId,
                type = InventoryManager.LogType.LOSS
            )
        }

        val creditAdj =
            if (reason == ClearReason.PURCHASE_RETURN && isCredit && creditAccountId != null)
                CreditReturnInfo(creditAccountId, totalCreditableInvoiceValue, returnRowId)
            else null

        ClearStockResult.Cleared(qty, reason, creditAdj)
    }

    /**
     * The bits the caller needs to run the supplier-balance adjustment through
     * CreditAdjustmentPrompt after a return is saved. [documentId] is the
     * PurchaseReturn row id, used as the idempotency key.
     */
    data class CreditReturnInfo(
        val accountId: Int,
        val amount: Double,
        val documentId: Int
    )

    sealed class ClearStockResult {
        object NoStock : ClearStockResult()
        data class Cleared(
            val quantity: Double,
            val reason: ClearReason,
            val creditAdjustment: CreditReturnInfo? = null
        ) : ClearStockResult()
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
        val totalIgst: Double,
        val creditAdjustment: CreditReturnInfo? = null
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
     * Differs from [clearRemainingStock] in three ways:
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
        creditAccountId: Int? = null
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

        // Moving-average redesign, Phase 2: read the average cost ONCE,
        // before any batch/stock reduction below touches it. Every row
        // inserted in the forEach below removes value at this same
        // frozen average (Phase 1 guarantees a purchase return can't
        // move it), so one read up-front is correct for every line.
        val avgAtTimeOfReturn = db.inventoryDao().getInventory(productId)?.averageCost ?: 0.0

        // Purchase-return-flow audit, Phase 1: this path used to leave
        // note_number/note_type/original_invoice_id and every GSTR-2
        // field at their bare Kotlin defaults (null / 0.0 / ""). Two
        // consequences of that, both silent: the backend's
        // validate_gstr2_fields() only runs when note_type == "D", so
        // it never ran on these rows at all; and /profit's variance sum
        // filters on note_type == "D" too, so the gain/loss this
        // function correctly computes below was being computed, saved,
        // and then invisibly excluded from every profit report. One
        // note number covers every line/batch in this single return,
        // same convention PurchaseReturnViewModel uses.
        val nextSeq = db.purchaseReturnDao().getMaxDebitNoteSequence() + 1
        val noteNumber = "DN-%05d".format(nextSeq)
        val noteDate = appNow()

        // Create separate entries for each batch individually
        resolved.forEach { (batch, qty) ->
            val parentPurchase = batch.purchaseInvoiceId?.let { db.purchaseDao().getById(it) }

            // Purchase-return-flow audit, Phase 4: when a batch never
            // recorded its own GST split (legacy/migration/synthetic
            // batches), this used to fall straight back to the
            // PRODUCT'S CURRENT tax rate — silently taxing a historical
            // return at whatever rate applies TODAY, not what actually
            // applied when the stock was purchased. Before falling that
            // far, try the actual purchase-item line this batch came
            // from: it recorded the real rate charged on that specific
            // invoice, which is a far better answer than "today's rate"
            // for anything but a brand-new batch.
            val historicalItem = batch.purchaseInvoiceId?.let { invId ->
                db.purchaseItemDao().getByPurchase(invId).find { it.productId == productId }
            }
            val batchHasNoGstRecorded =
                batch.cgstPercent == 0.0 && batch.sgstPercent == 0.0 && batch.igstPercent == 0.0

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
                    // Fallback based on batch, then the original purchase
                    // item, then (last resort) the product's current IGST.
                    val igstPct = when {
                        !batchHasNoGstRecorded -> batch.igstPercent
                        historicalItem != null -> historicalItem.purchaseIgstPercentage
                        product != null -> product.igstPercentage
                        else -> 0.0
                    }
                    sameStateForThisBatch = igstPct <= 0.0
                    batchStateName = if (sameStateForThisBatch) shopStateName else "Other State"
                }
            }

            val taxable = qty * batch.unitCostExcludingTax

            // Same three-tier fallback for the actual line percentages:
            // the batch's own recorded rate first, then the rate that
            // was actually charged on the originating invoice, and only
            // as a last resort (no batch data, no invoice line found)
            // today's product rate.
            val cgstPct = when {
                !batchHasNoGstRecorded -> batch.cgstPercent
                historicalItem != null -> historicalItem.purchaseCgstPercentage
                product != null -> product.cgstPercentage
                else -> 0.0
            }
            val sgstPct = when {
                !batchHasNoGstRecorded -> batch.sgstPercent
                historicalItem != null -> historicalItem.purchaseSgstPercentage
                product != null -> product.sgstPercentage
                else -> 0.0
            }
            val igstPct = when {
                !batchHasNoGstRecorded -> batch.igstPercent
                historicalItem != null -> historicalItem.purchaseIgstPercentage
                product != null -> product.igstPercentage
                else -> 0.0
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
                    creditAccountId  = creditAccountId,

                    // Moving-average redesign, Phase 2: value leaving
                    // inventory at the frozen current average, minus what
                    // the supplier refunds at this batch's own original
                    // cost. Positive = loss, negative = gain.
                    inventoryValuationVariance = Math.round(
                        ((qty * avgAtTimeOfReturn) - (qty * batch.unitCostExcludingTax)) * 100.0
                    ) / 100.0,

                    // Purchase-return-flow audit, Phase 1: same note
                    // identity for every batch/line in this call — this
                    // is what makes the row visible to the backend's
                    // GSTR-2 validation and to /profit's variance sum,
                    // and what makes "already returned against this
                    // invoice" queries see it. originalInvoiceId is this
                    // SPECIFIC batch's own invoice (batches picked in one
                    // call can legitimately span different invoices).
                    noteNumber             = noteNumber,
                    noteDate               = noteDate,
                    noteType               = "D",
                    originalInvoiceId      = batch.purchaseInvoiceId,
                    originalInvoiceNumber  = parentPurchase?.invoiceNumber,
                    originalInvoiceDate    = parentPurchase?.invoiceDate,
                    placeOfSupply          = shopStateName,
                    placeOfSupplyCode      = shopStateCode,
                    supplyType             = if (sameStateForThisBatch) "intrastate" else "interstate",
                    documentType           = "Debit Note",
                    documentNature         = "Debit Note",
                    documentSeries         = "DN",
                    preGst                 = "N",
                    reasonForIssuingDocument = "Purchase return",
                    noteRefundVoucherValue = roundedInvoice,
                    rate                   = 0.0,
                    eligibilityForItc      = "Inputs",
                    availedItcIntegratedTax = 0.0,
                    availedItcCentralTax   = 0.0,
                    availedItcStateTax     = 0.0,
                    availedItcCess         = 0.0,
                    invoiceType            = "Regular"
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

        // Avg-cost audit, Fix 2 follow-up: the batch debit (and its drift
        // reconcile) must happen BEFORE InventoryManager.reduceStock(), not
        // after. reduceBatches/reconcileDrift are what actually recompute
        // average cost from what's left in the batch ledger; reduceStock is
        // what writes this event's InventoryLog, stamping it with whatever
        // inventory.averageCost happens to be at that instant
        // (resultingAverageCost, the value Fix 2 has the backend trust
        // outright). With reduceStock running first (the original order),
        // that log captured a stale pre-recompute average cost, and that
        // stale number is exactly what got pushed to the server as the
           // Inventory row + log + transaction. skipBatchConsume = true
        // because the per-batch debit will happen below.
        InventoryManager.reduceStock(
            db = db,
            productId = productId,
            quantity = grandTotalQuantity,
            type = InventoryManager.LogType.PURCHASE_RETURN,
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

        // Supplier-balance adjustment lifted OUT — the caller runs it through
        // CreditAdjustmentPrompt after this returns, so it is clamped to the
        // account balance and asks cash-vs-advance on an overshoot. The values
        // returned in BatchReturnResult are what that prompt uses for totals.
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

    /**
     * Records scrap for multiple batches, valuing each batch at its own net cost.
     * Returns null if requested lines are invalid.
     */
    suspend fun scrapByBatches(
        productId: Int,
        productName: String,
        variantName: String?,
        hsnCode: String?,
        lines: List<BatchScrapLine>
    ): BatchScrapResult? = db.withTransaction {

        if (lines.isEmpty()) return@withTransaction null

        val batchDao = db.purchaseBatchDao()
        val resolved = lines.mapNotNull { line ->
            val b = batchDao.getBatchById(line.batchId) ?: return@withTransaction null
            if (b.productId != productId) return@withTransaction null
            val qty = minOf(line.quantity, b.quantityRemaining)
            if (qty <= 0.0) return@withTransaction null
            b to qty
        }
        if (resolved.isEmpty()) return@withTransaction null

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
            type = InventoryManager.LogType.LOSS,
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
    suspend fun getRemainingBatchesForProduct(productId: Int): List<com.example.easy_billing.db.PurchaseBatch> {
        db.purchaseBatchDao().purgeDriftCorrectionBatches()
        return db.purchaseBatchDao().getRemainingBatches(productId)
    }

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

