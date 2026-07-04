package com.example.easy_billing.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.db.CreditNote
import com.example.easy_billing.repository.CreditNoteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SalesReturnViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CreditNoteRepository.get(app)
    private val db   = AppDatabase.getDatabase(app)

    // ── Bill + items ─────────────────────────────────────────────────────────
    private val _bill      = MutableStateFlow<Bill?>(null)
    val bill: StateFlow<Bill?> = _bill.asStateFlow()

    private val _billItems = MutableStateFlow<List<BillItem>>(emptyList())
    val billItems: StateFlow<List<BillItem>> = _billItems.asStateFlow()

    /** For each productId: how much has already been returned on this bill. */
    private val _alreadyReturnedMap = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val alreadyReturnedMap: StateFlow<Map<Int, Double>> = _alreadyReturnedMap.asStateFlow()

    // ── Prior credit notes for this bill ─────────────────────────────────────
    private val _priorNotes = MutableStateFlow<List<CreditNote>>(emptyList())
    val priorNotes: StateFlow<List<CreditNote>> = _priorNotes.asStateFlow()

    // ── UI state ─────────────────────────────────────────────────────────────
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _result = MutableStateFlow<CreditNoteRepository.Result?>(null)
    val result: StateFlow<CreditNoteRepository.Result?> = _result.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────

    fun loadBill(billId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _bill.value      = db.billDao().getBillById(billId)
            val items        = db.billItemDao().getItemsForBill(billId)
            _billItems.value = items

            // Build the map of already-returned quantities
            val map = mutableMapOf<Int, Double>()
            for (item in items) {
                map[item.productId] = repo.alreadyReturnedQty(billId, item.productId)
            }
            _alreadyReturnedMap.value = map
            _priorNotes.value         = repo.getByBill(billId)
            _isLoading.value          = false
        }
    }

    /**
     * Maximum quantity the user is allowed to return for [productId] on this bill.
     * Equals soldQty − alreadyReturnedQty.
     */
    fun maxReturnableQty(productId: Int, soldQty: Double): Double {
        val returned = _alreadyReturnedMap.value[productId] ?: 0.0
        return (soldQty - returned).coerceAtLeast(0.0)
    }

    fun submitReturn(
        billId: Int,
        billNumber: String,
        billDateMillis: Long,
        customerName: String,
        customerGstin: String?,
        placeOfSupply: String,
        reverseCharge: String,
        supplyType: String,
        urType: String,
        lines: List<CreditNoteRepository.ReturnLine>
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _result.value    = repo.createCreditNote(
                billId            = billId,
                billNumber        = billNumber,
                billDateMillis    = billDateMillis,
                customerName      = customerName,
                customerGstin     = customerGstin,
                placeOfSupply     = placeOfSupply,
                reverseCharge     = reverseCharge,
                supplyType        = supplyType,
                urType            = urType,
                lines             = lines
            )
            _isLoading.value = false
        }
    }

    fun clearResult() { _result.value = null }
}
