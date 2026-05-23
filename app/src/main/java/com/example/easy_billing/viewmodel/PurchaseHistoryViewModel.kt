package com.example.easy_billing.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Purchase
import com.example.easy_billing.db.PurchaseItem
import com.example.easy_billing.db.PurchaseReturn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PurchaseHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getDatabase(app)

    // ── Purchase list (paginated via Room LIMIT) ──────────────────────────────
    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())
    val purchases: StateFlow<List<Purchase>> = _purchases.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── Selected purchase detail ──────────────────────────────────────────────
    private val _selectedPurchase = MutableStateFlow<Purchase?>(null)
    val selectedPurchase: StateFlow<Purchase?> = _selectedPurchase.asStateFlow()

    private val _selectedItems = MutableStateFlow<List<PurchaseItem>>(emptyList())
    val selectedItems: StateFlow<List<PurchaseItem>> = _selectedItems.asStateFlow()

    private val _returnsForSelected = MutableStateFlow<List<PurchaseReturn>>(emptyList())
    val returnsForSelected: StateFlow<List<PurchaseReturn>> = _returnsForSelected.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────

    init { loadPurchases() }

    fun loadPurchases(limit: Int = 200) {
        viewModelScope.launch {
            _isLoading.value  = true
            _purchases.value  = db.purchaseDao().getRecent(limit)
            _isLoading.value  = false
        }
    }

    fun loadPurchaseDetail(purchaseId: Int) {
        viewModelScope.launch {
            _isLoading.value         = true
            _selectedPurchase.value  = db.purchaseDao().getById(purchaseId)
            _selectedItems.value     = db.purchaseItemDao().getByPurchase(purchaseId)
            _returnsForSelected.value = db.purchaseReturnDao().getByOriginalInvoice(purchaseId)
            _isLoading.value         = false
        }
    }

    /**
     * Total quantity of [productId] already returned against [purchaseId].
     * Exposed so PurchaseReturnActivity can guard against over-returns.
     */
    suspend fun alreadyReturnedQty(purchaseId: Int, productId: Int): Double =
        db.purchaseReturnDao().getTotalReturnedForInvoiceProduct(purchaseId, productId)

    fun clearSelectedDetail() {
        _selectedPurchase.value   = null
        _selectedItems.value      = emptyList()
        _returnsForSelected.value = emptyList()
    }
}
