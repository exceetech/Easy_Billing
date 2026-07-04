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

class DebitNoteViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CreditNoteRepository.get(app)
    private val db   = AppDatabase.getDatabase(app)

    // ── Bill + items ─────────────────────────────────────────────────────────
    private val _bill      = MutableStateFlow<Bill?>(null)
    val bill: StateFlow<Bill?> = _bill.asStateFlow()

    private val _billItems = MutableStateFlow<List<BillItem>>(emptyList())
    val billItems: StateFlow<List<BillItem>> = _billItems.asStateFlow()

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

            _priorNotes.value         = repo.getByBill(billId)
            _isLoading.value          = false
        }
    }

    fun submitDebitNote(
        billId: Int,
        billNumber: String,
        billDateMillis: Long,
        customerName: String,
        customerGstin: String?,
        placeOfSupply: String,
        reverseCharge: String,
        supplyType: String,
        noteSupplyType: String,
        urType: String,
        lines: List<CreditNoteRepository.DebitLine>
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _result.value    = repo.createDebitNote(
                billId            = billId,
                billNumber        = billNumber,
                billDateMillis    = billDateMillis,
                customerName      = customerName,
                customerGstin     = customerGstin,
                placeOfSupply     = placeOfSupply,
                reverseCharge     = reverseCharge,
                supplyType        = supplyType,
                noteSupplyType    = noteSupplyType,
                urType            = urType,
                lines             = lines
            )
            _isLoading.value = false
        }
    }

    fun clearResult() { _result.value = null }
}
