package com.example.easy_billing.viewmodel

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
    fun submitReturn(lines: Map<PurchaseItem, Double>) {
        viewModelScope.launch {
            val p = _purchase.value ?: return@launch

            // ── Validate ──────────────────────────────────────────────────────
            if (lines.isEmpty() || lines.values.all { it <= 0.0 }) {
                _result.value = Result.ValidationError("Enter at least one return quantity.")
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
                val max = maxReturnableQty(item.productId, item.quantity)
                if (qty > max) {
                    _result.value = Result.ValidationError(
                        "'${item.productName}': max returnable is %.2f.".format(max)
                    )
                    return@launch
                }
            }

            _isLoading.value = true

            try {
                db.withTransaction {

                    // ── Generate DN number ────────────────────────────────────
                    val nextSeq    = db.purchaseReturnDao().getMaxDebitNoteSequence() + 1
                    val noteNumber = "DN-%05d".format(nextSeq)
                    val now        = System.currentTimeMillis()

                    val store = db.storeInfoDao().get()
                    val gst   = db.gstProfileDao().get()
                    val shopIdStr = gst?.shopId?.takeIf { it.isNotBlank() }
                        ?: store?.gstin.orEmpty()
                    val stateCode = gst?.stateCode?.takeIf { it.isNotBlank() }
                        ?: GstEngine.getStateCode(store?.gstin)
                    val stateName = GstEngine.INDIA_STATES[stateCode] ?: stateCode

                    for ((item, qty) in lines) {
                        if (qty <= 0.0) continue

                        val productId = item.productId ?: continue

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
                        val round    = { v: Double -> Math.round(v * 100.0) / 100.0 }

                        // ── Debit from exact purchase batch ───────────────────
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

                        // Reduce the inventory row (no second batch pass).
                        InventoryManager.reduceStock(
                            db, productId, qty - qtyToDebit, "RETURN", skipBatchConsume = true
                        )

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
                                noteType              = "D",
                                originalInvoiceId     = p.id,
                                originalInvoiceNumber = p.invoiceNumber,
                                originalInvoiceDate   = p.invoiceDate,
                                placeOfSupply         = stateName,
                                supplyType            = if (sameState) "intrastate" else "interstate",
                                cessAmount            = 0.0
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
