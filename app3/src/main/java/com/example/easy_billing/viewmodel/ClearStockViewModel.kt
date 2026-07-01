package com.example.easy_billing.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.repository.InventoryReductionRepository
import com.example.easy_billing.repository.InventoryReductionRepository.ClearReason
import com.example.easy_billing.repository.InventoryReductionRepository.ClearStockResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the clear-stock dialog (Inventory screen). The dialog has
 * exactly two routes — Purchase Return and Scrap — and quantity is
 * derived automatically from the inventory row.
 */
class ClearStockViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = InventoryReductionRepository.get(app)
    private val db = AppDatabase.getDatabase(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun clear(productId: Int, reason: ClearReason) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val product = db.productDao().getById(productId)
                    ?: throw IllegalStateException("Product not found")

                repo.clearRemainingStock(
                    productId   = productId,
                    productName = product.name,
                    variantName = product.variant,
                    hsnCode     = product.hsnCode,
                    reason      = reason,
                    purchaseTaxCgst = product.cgstPercentage,
                    purchaseTaxSgst = product.sgstPercentage,
                    purchaseTaxIgst = product.igstPercentage
                )
            }.onSuccess { result ->
                _state.value = UiState(
                    loading = false,
                    cleared = result is ClearStockResult.Cleared,
                    quantity = (result as? ClearStockResult.Cleared)?.quantity
                )
                com.example.easy_billing.sync.SyncCoordinator
                    .get(getApplication()).requestSync()
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    loading = false, error = err.message ?: "Failed"
                )
            }
        }
    }

    fun clearTransient() { _state.value = UiState() }

    data class UiState(
        val loading: Boolean = false,
        val cleared: Boolean = false,
        val quantity: Double? = null,
        val error: String? = null
    )
}
