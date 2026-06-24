package com.example.easy_billing.viewmodel

import com.example.easy_billing.util.appNow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.easy_billing.InventoryValuation
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Purchase
import com.example.easy_billing.db.PurchaseItem
import com.example.easy_billing.db.PurchaseReturn
import com.example.easy_billing.util.GstEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PurchaseReturnViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getDatabase(app)

    // ── State ─────────────────────────────────────────────────────────────────
    private val _purchase       = MutableStateFlow<Purchase?>(null)
    val purchase: StateFlow<Purchase?> = _purchase.asStateFlow()

    private val _purchaseItems  = MutableStateFlow<List<PurchaseItem>>(emptyList())
    val purchaseItems: StateFlow<List<PurchaseItem>> = _purchaseItems.asStateFlow()

    /** For each productId: already-returned qty against this invoice. */
    private val _alreadyReturned = MutableStateFlow<Map<Int?, Double>>(emptyMap())
    val alreadyReturned: StateFlow<Map<Int?, Double>> = _alreadyReturned.asStateFlow()

    private val _isLoading      = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _shopStateCode = MutableStateFlow("")
    val shopStateCode: StateFlow<String> = _shopStateCode.asStateFlow()

    sealed class Result {
        data class Success(val noteNumber: String) : Result()
        data class ValidationError(val message: String) : Result()
        data class SaveError(val cause: Throwable) : Result()
    }

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────

    fun loadPurchase(purchaseId: Int) {
        viewModelScope.launch {
            _isLoading.value  = true
            val p             = db.purchaseDao().getById(purchaseId) ?: return@launch
            val items         = db.purchaseItemDao().getByPurchase(purchaseId)
            _purchase.value   = p

            val store = db.storeInfoDao().get()
            val gstProfile = db.gstProfileDao().get()
            val stateCode = gstProfile?.stateCode?.takeIf { it.isNotBlank() }
                ?: GstEngine.getStateCode(store?.gstin)
            _shopStateCode.value = stateCode

            _purchaseItems.value = items

            val map = mutableMapOf<Int?, Double>()
            for (item in items) {
                if (item.productId != null) {
                    map[item.productId] = db.purchaseReturnDao()
                        .getTotalReturnedForInvoiceProduct(purchaseId, item.productId)
                }
            }
            _alreadyReturned.value = map
            _isLoading.value       = false
        }
    }

    fun maxReturnableQty(productId: Int?, purchasedQty: Double): Double {
        val returned = _alreadyReturned.value[productId] ?: 0.0
        return (purchasedQty - returned).coerceAtLeast(0.0)
    }

    /**
     * Saves a Debit Note / Purchase Return.
     *
     * Architecture rules enforced here:
     *  • Debit note number is generated sequentially (DN-XXXXX).
     *  • Stock is deducted from the **exact batch** belonging to [purchaseId]
     *    via [InventoryValuation.reduceBatches] — NOT generic FIFO.
     *  • [InventoryManager.reduceStock] is called with skipBatchConsume=true
     *    so there is no double-debit.
     *
     * @param lines  Map of PurchaseItem → returnQty.
     */


    fun submitReturn(
        lines: Map<PurchaseItem, Double>,
        noteType: String = "D",
        preGst: String = "N",
        documentType: String = "Debit Note",
        reasonForIssuingDocument: String = "Purchase return",
        noteRefundVoucherValue: Double = 0.0,
        rate: Double = 0.0,
        eligibilityForItc: String = "Inputs",
        availedItcIntegratedTax: Double = 0.0,
        availedItcCentralTax: Double = 0.0,
        availedItcStateTax: Double = 0.0,
        availedItcCess: Double = 0.0,
        invoiceType: String = "Regular",
        placeOfSupplyCode: String = ""
    ) {
        viewModelScope.launch {
            val p = _purchase.value ?: return@launch

            // ── Validate ──────────────────────────────────────────────────────
            val actionLabel = if (noteType == "D") "return" else "additional"
            if (lines.isEmpty() || lines.values.all { it <= 0.0 }) {
                _result.value = Result.ValidationError("Enter at least one $actionLabel quantity.")
                return@launch
            }
            for ((item, qty) in lines) {
                if (qty < 0.0) {
                    _result.value = Result.ValidationError(
                        "Quantity for '${item.productName}' cannot be negative."
                    )
                    return@launch
                }
                if (qty == 0.0) continue
                if (noteType == "D") {
                    val max = maxReturnableQty(item.productId, item.quantity)
                    if (qty > max) {
                        _result.value = Result.ValidationError(
                            "'${item.productName}': max returnable is %.2f.".format(max)
                        )
                        return@launch
                    }
                }
            }

            val store = db.storeInfoDao().get()
            val gst   = db.gstProfileDao().get()
            val stateCode = gst?.stateCode?.takeIf { it.isNotBlank() }
                ?: GstEngine.getStateCode(store?.gstin)

            // Calculate actual totals to validate against
            var cgstTotal = 0.0
            var sgstTotal = 0.0
            var igstTotal = 0.0
            var cessTotal = 0.0
            var taxableTotal = 0.0

            for ((item, qty) in lines) {
                if (qty <= 0.0) continue
                val supplierState = GstEngine.getStateCodeFromName(p.state) ?: GstEngine.getStateCode(p.supplierGstin)
                val sameState = if (stateCode.isNotBlank() && supplierState.isNotBlank()) stateCode == supplierState else item.purchaseIgstPercentage <= 0.0
                val unitTaxable = if (item.quantity > 0.0) item.taxableAmount / item.quantity else 0.0
                val taxable = qty * unitTaxable
                val cgstAmt = if (sameState) taxable * item.purchaseCgstPercentage / 100.0 else 0.0
                val sgstAmt = if (sameState) taxable * item.purchaseSgstPercentage / 100.0 else 0.0
                val igstAmt = if (!sameState) taxable * item.purchaseIgstPercentage / 100.0 else 0.0
                val cessAmt = if (item.quantity > 0.0) (qty / item.quantity) * item.cessAmount else 0.0

                cgstTotal += cgstAmt
                sgstTotal += sgstAmt
                igstTotal += igstAmt
                cessTotal += cessAmt
                taxableTotal += taxable
            }

            val round = { v: Double -> Math.round(v * 100.0) / 100.0 }
            val rCgst = round(cgstTotal)
            val rSgst = round(sgstTotal)
            val rIgst = round(igstTotal)
            val rCess = round(cessTotal)
            val rTaxable = round(taxableTotal)

            // Perform GSTR-2 validations
            if (preGst != "Y" && preGst != "N") {
                _result.value = Result.ValidationError("Pre GST must be Y or N.")
                return@launch
            }
            if (documentType.isBlank()) {
                _result.value = Result.ValidationError("Document Type is required.")
                return@launch
            }
            if (reasonForIssuingDocument.isBlank()) {
                _result.value = Result.ValidationError("Reason for Issuing Document is required.")
                return@launch
            }
            if (noteRefundVoucherValue <= 0) {
                _result.value = Result.ValidationError("Note/Refund Voucher Value must be > 0.")
                return@launch
            }
            if (noteRefundVoucherValue < rTaxable) {
                _result.value = Result.ValidationError("Note/Refund Voucher Value must stay >= taxable value (₹%.2f).".format(rTaxable))
                return@launch
            }
            if (rate < 0) {
                _result.value = Result.ValidationError("Rate must be >= 0.")
                return@launch
            }
            if (eligibilityForItc.isBlank()) {
                _result.value = Result.ValidationError("Eligibility for ITC is required.")
                return@launch
            }
            if (availedItcIntegratedTax < 0 || availedItcCentralTax < 0 || availedItcStateTax < 0 || availedItcCess < 0) {
                _result.value = Result.ValidationError("Availed ITC fields must be >= 0.")
                return@launch
            }
            if (eligibilityForItc in listOf("Ineligible", "None")) {
                if (availedItcIntegratedTax != 0.0 || availedItcCentralTax != 0.0 || availedItcStateTax != 0.0 || availedItcCess != 0.0) {
                    _result.value = Result.ValidationError("If eligibility for ITC is Ineligible/None, availed ITC values must be 0.")
                    return@launch
                }
            }
            if (availedItcIntegratedTax > rIgst) {
                _result.value = Result.ValidationError("Availed ITC Integrated Tax (₹%.2f) cannot exceed IGST (₹%.2f).".format(availedItcIntegratedTax, rIgst))
                return@launch
            }
            if (availedItcCentralTax > rCgst) {
                _result.value = Result.ValidationError("Availed ITC Central Tax (₹%.2f) cannot exceed CGST (₹%.2f).".format(availedItcCentralTax, rCgst))
                return@launch
            }
            if (availedItcStateTax > rSgst) {
                _result.value = Result.ValidationError("Availed ITC State/UT Tax (₹%.2f) cannot exceed SGST (₹%.2f).".format(availedItcStateTax, rSgst))
                return@launch
            }
            if (availedItcCess > rCess) {
                _result.value = Result.ValidationError("Availed ITC Cess (₹%.2f) cannot exceed Cess (₹%.2f).".format(availedItcCess, rCess))
                return@launch
            }
            if (invoiceType.isBlank()) {
                _result.value = Result.ValidationError("Invoice Type is required.")
                return@launch
            }
            if (placeOfSupplyCode.isBlank()) {
                _result.value = Result.ValidationError("Place of Supply Code is required.")
                return@launch
            }

            _isLoading.value = true

            try {
                db.withTransaction {

                    // ── Generate note number ────────────────────────────────────
                    val nextSeq = if (noteType == "D") {
                        db.purchaseReturnDao().getMaxDebitNoteSequence() + 1
                    } else {
                        db.purchaseReturnDao().getMaxCreditNoteSequence() + 1
                    }
                    val prefix = if (noteType == "D") "DN" else "CN"
                    val noteNumber = "$prefix-%05d".format(nextSeq)
                    val now        = appNow()

                    val shopIdStr = gst?.shopId?.takeIf { it.isNotBlank() }
                        ?: store?.gstin.orEmpty()
                    val stateName = GstEngine.INDIA_STATES[stateCode] ?: stateCode

                    var totalNoteInvoiceValue = 0.0
                    val itemsToSave = mutableListOf<Triple<PurchaseItem, Double, Double>>()

                    for ((item, qty) in lines) {
                        if (qty <= 0.0) continue
                        if (item.productId == null) continue

                        // Determine intra/interstate
                        val supplierState = GstEngine.getStateCodeFromName(p.state)
                            ?: GstEngine.getStateCode(p.supplierGstin)
                        val sameState     = if (stateCode.isNotBlank() && supplierState.isNotBlank())
                            stateCode == supplierState
                        else
                            item.purchaseIgstPercentage <= 0.0

                        val unitTaxable = if (item.quantity > 0.0) item.taxableAmount / item.quantity else 0.0
                        val taxable  = qty * unitTaxable
                        val cgstAmt  = if (sameState) taxable * item.purchaseCgstPercentage / 100.0 else 0.0
                        val sgstAmt  = if (sameState) taxable * item.purchaseSgstPercentage / 100.0 else 0.0
                        val igstAmt  = if (!sameState) taxable * item.purchaseIgstPercentage / 100.0 else 0.0
                        val invoice  = taxable + cgstAmt + sgstAmt + igstAmt

                        totalNoteInvoiceValue += round(invoice)
                        itemsToSave.add(Triple(item, qty, invoice))
                    }

                    // ── Optional Credit/Debit Account Adjustment ───────────────
                    var creditTxId: Int? = null
                    if (p.isCredit && p.creditAccountId != null) {
                        val shopIdInt = getApplication<android.app.Application>()
                            .getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                            .getInt("SHOP_ID", 1)
                        val account = db.creditAccountDao().getById(p.creditAccountId, shopIdInt)
                        if (account != null) {
                            val adjustAmount = if (noteType == "D") -totalNoteInvoiceValue else totalNoteInvoiceValue
                            val newDue = account.dueAmount + adjustAmount
                            db.creditAccountDao().updateDue(account.id, newDue, shopIdInt)

                            db.creditTransactionDao().insert(
                                com.example.easy_billing.db.CreditTransaction(
                                    accountId = account.id,
                                    shopId = shopIdInt,
                                    amount = adjustAmount,
                                    type = if (noteType == "D") "PURCHASE_RETURN" else "PURCHASE_CREDIT",
                                    referenceInvoice = noteNumber,
                                    isSynced = false
                                )
                            )
                        }
                    }

                    // Now process the inventory updates and insert the records
                    for ((item, qty, invoice) in itemsToSave) {
                        val productId = item.productId ?: continue
                        val unitTaxable = if (item.quantity > 0.0) item.taxableAmount / item.quantity else 0.0
                        val taxable  = qty * unitTaxable

                        val supplierState = GstEngine.getStateCodeFromName(p.state)
                            ?: GstEngine.getStateCode(p.supplierGstin)
                        val sameState     = if (stateCode.isNotBlank() && supplierState.isNotBlank())
                            stateCode == supplierState
                        else
                            item.purchaseIgstPercentage <= 0.0

                        val cgstAmt  = if (sameState) taxable * item.purchaseCgstPercentage / 100.0 else 0.0
                        val sgstAmt  = if (sameState) taxable * item.purchaseSgstPercentage / 100.0 else 0.0
                        val igstAmt  = if (!sameState) taxable * item.purchaseIgstPercentage / 100.0 else 0.0
                        val cessAmt  = if (item.quantity > 0.0) (qty / item.quantity) * item.cessAmount else 0.0

                        if (noteType == "D") {
                            // ── Debit Note: reduce stock and batch ───────────────────
                            val batches = db.purchaseBatchDao()
                                .getRemainingBatches(productId)
                                .filter { it.purchaseInvoiceId == p.id && it.quantityRemaining > 0.0 }

                            var qtyToDebit = qty
                            val reductions = mutableListOf<InventoryValuation.BatchReduction>()
                            for (b in batches) {
                                if (qtyToDebit <= 0.0) break
                                val take = minOf(b.quantityRemaining, qtyToDebit)
                                reductions.add(InventoryValuation.BatchReduction(b.id, take))
                                qtyToDebit -= take
                            }

                            // Reduce the inventory row (no second batch pass).
                            val qtyToReduceRow = qty - qtyToDebit
                            if (qtyToReduceRow > 0.0) {
                                InventoryManager.reduceStock(
                                    db, productId, qtyToReduceRow, "RETURN", skipBatchConsume = true
                                )
                            }

                            if (reductions.isNotEmpty()) {
                                InventoryValuation.reduceBatches(db, productId, reductions)
                            }

                            // If the invoice's batch is already exhausted fall back
                            // to FIFO for the remainder (shouldn't normally happen).
                            if (qtyToDebit > 0.0) {
                                InventoryManager.reduceStock(
                                    db, productId, qtyToDebit, "RETURN", skipBatchConsume = false
                                )
                            }
                        } else {
                            // ── Credit Note: increase stock and batch ────────────────
                            val unitCostGross = if (item.quantity > 0.0) item.invoiceValue / item.quantity else 0.0
                            InventoryManager.addStock(
                                db = db,
                                productId = productId,
                                quantity = qty,
                                costPrice = unitCostGross,
                                batchMeta = InventoryManager.StockBatchMeta(
                                    purchaseInvoiceId = p.id,
                                    supplierName = p.supplierName,
                                    supplierGstin = p.supplierGstin,
                                    invoiceNumber = p.invoiceNumber,
                                    batchCode = noteNumber,
                                    unitCostExcludingTax = unitCostGross,
                                    gstPercent = item.purchaseCgstPercentage + item.purchaseSgstPercentage + item.purchaseIgstPercentage,
                                    cgstPercent = item.purchaseCgstPercentage,
                                    sgstPercent = item.purchaseSgstPercentage,
                                    igstPercent = item.purchaseIgstPercentage,
                                    invoiceValue = invoice,
                                    taxableValue = taxable
                                )
                            )
                        }

                        // Distribute availed ITC and note refund voucher values proportionally
                        val rowAvailedIgst = if (rIgst > 0.0) availedItcIntegratedTax * (round(igstAmt) / rIgst) else 0.0
                        val rowAvailedCgst = if (rCgst > 0.0) availedItcCentralTax * (round(cgstAmt) / rCgst) else 0.0
                        val rowAvailedSgst = if (rSgst > 0.0) availedItcStateTax * (round(sgstAmt) / rSgst) else 0.0
                        val rowAvailedCess = if (rCess > 0.0) availedItcCess * (round(cessAmt) / rCess) else 0.0

                        val rowAvailedIgstClamped = minOf(round(igstAmt), round(rowAvailedIgst))
                        val rowAvailedCgstClamped = minOf(round(cgstAmt), round(rowAvailedCgst))
                        val rowAvailedSgstClamped = minOf(round(sgstAmt), round(rowAvailedSgst))
                        val rowAvailedCessClamped = minOf(round(cessAmt), round(rowAvailedCess))

                        var rowVoucherVal = if (totalNoteInvoiceValue > 0.0) {
                            noteRefundVoucherValue * (round(invoice) / totalNoteInvoiceValue)
                        } else {
                            round(invoice)
                        }
                        if (rowVoucherVal < round(taxable)) {
                            rowVoucherVal = round(taxable)
                        }

                        // ── Insert PurchaseReturn row ─────────────────────────
                        db.purchaseReturnDao().insert(
                            PurchaseReturn(
                                shopId                = shopIdStr,
                                productId             = productId,
                                productName           = item.productName,
                                variantName           = item.variant,
                                hsnCode               = item.hsnCode,
                                quantityReturned      = qty,
                                taxableAmount         = round(taxable),
                                invoiceValue          = round(invoice),
                                cgstPercentage        = if (sameState) item.purchaseCgstPercentage else 0.0,
                                sgstPercentage        = if (sameState) item.purchaseSgstPercentage else 0.0,
                                igstPercentage        = if (!sameState) item.purchaseIgstPercentage else 0.0,
                                cgstAmount            = round(cgstAmt),
                                sgstAmount            = round(sgstAmt),
                                igstAmount            = round(igstAmt),
                                state                 = p.state.ifBlank { stateName },
                                supplierGstin         = p.supplierGstin,
                                supplierName          = p.supplierName,
                                isSynced              = false,
                                noteNumber            = noteNumber,
                                noteDate              = now,
                                noteType              = noteType,
                                originalInvoiceId     = p.id,
                                originalInvoiceNumber = p.invoiceNumber,
                                originalInvoiceDate   = p.invoiceDate,
                                placeOfSupply         = stateName,
                                supplyType            = if (sameState) "intrastate" else "interstate",
                                cessAmount            = round(cessAmt),
                                documentType          = if (noteType == "D") documentType else "Credit Note",
                                documentNature        = if (noteType == "D") "Debit Note" else "Credit Note",
                                documentSeries        = prefix,
                                isCredit              = p.isCredit,
                                creditAccountId       = p.creditAccountId,
                                creditTransactionId   = creditTxId,

                                // GSTR-2 columns
                                preGst                = preGst,
                                reasonForIssuingDocument = reasonForIssuingDocument,
                                noteRefundVoucherValue = round(rowVoucherVal),
                                rate                  = rate,
                                eligibilityForItc     = eligibilityForItc,
                                availedItcIntegratedTax = rowAvailedIgstClamped,
                                availedItcCentralTax  = rowAvailedCgstClamped,
                                availedItcStateTax    = rowAvailedSgstClamped,
                                availedItcCess        = rowAvailedCessClamped,
                                invoiceType           = invoiceType,
                                placeOfSupplyCode     = placeOfSupplyCode
                            )
                        )
                    }
                    _result.value = Result.Success(noteNumber)
                }
            } catch (e: Exception) {
                _result.value = Result.SaveError(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearResult() { _result.value = null }
}
