package com.example.easy_billing.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Product
import com.example.easy_billing.repository.ProductRepository
import com.example.easy_billing.sync.SyncCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs [com.example.easy_billing.EditProductActivity].
 *
 * Splits the save path in two:
 *   • [savePurchased] uses [ProductRepository.updateSalesFieldsOnly]
 *     so trackInventory + stock are guaranteed untouched.
 *   • [saveManual]    does a full upsert and (optionally) calls
 *     [InventoryManager.addStock] when the user added stock.
 */
class EditProductViewModel(app: Application) : AndroidViewModel(app) {

    private val productRepo = ProductRepository.get(app)
    private val db = AppDatabase.getDatabase(app)

    private val _product = MutableStateFlow<Product?>(null)
    val product: StateFlow<Product?> = _product.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _ui.asStateFlow()

    fun load(id: Int) {
        viewModelScope.launch {
            _product.value = withContext(Dispatchers.IO) { productRepo.getById(id) }
            if (_product.value == null) {
                _ui.value = _ui.value.copy(error = "Product not found")
            }
        }
    }

    /* ------------------------------------------------------------------
     *  Purchased — restricted save
     * ------------------------------------------------------------------ */

    fun savePurchased(
        price: Double,
        hsn: String?,
        cgst: Double, sgst: Double, igst: Double
    ) {
        val current = _product.value ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(saving = true, error = null)
            runCatching {
                productRepo.updateSalesFieldsOnly(
                    productId = current.id,
                    price = price,
                    cgst = cgst, sgst = sgst, igst = igst,
                    hsn = hsn
                )
                SyncCoordinator.get(getApplication()).requestSync()
            }.onSuccess {
                _ui.value = UiState(saving = false, savedAt = System.currentTimeMillis())
            }.onFailure {
                _ui.value = _ui.value.copy(saving = false, error = it.message ?: "Save failed")
            }
        }
    }

    /* ------------------------------------------------------------------
     *  Manual — full save (+ optional add stock)
     * ------------------------------------------------------------------ */

    fun saveManual(
        price: Double,
        hsn: String?,
        cgst: Double, sgst: Double, igst: Double,
        trackInventory: Boolean,
        addStockQuantity: Double
    ) {
        val current = _product.value ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(saving = true, error = null)
            runCatching {
                val combined = (cgst + sgst).takeIf { it > 0 } ?: igst
                val updated = current.copy(
                    price = price,
                    hsnCode = hsn,
                    cgstPercentage = cgst,
                    sgstPercentage = sgst,
                    igstPercentage = igst,
                    defaultGstRate = combined,
                    trackInventory = trackInventory
                )
                productRepo.upsert(updated)

                if (trackInventory && addStockQuantity > 0) {
                    // Manual products carry no cost basis (per spec).
                    // Use 0 so inventory averageCost reflects the
                    // truth — call sites that need a real cost go
                    // through PurchaseRepository instead.
                    InventoryManager.addStock(
                        db = db,
                        productId = current.id,
                        quantity = addStockQuantity,
                        costPrice = 0.0
                    )
                }

                SyncCoordinator.get(getApplication()).requestSync()
            }.onSuccess {
                _ui.value = UiState(saving = false, savedAt = System.currentTimeMillis())
            }.onFailure {
                _ui.value = _ui.value.copy(saving = false, error = it.message ?: "Save failed")
            }
        }
    }

    fun clearTransient() {
        _ui.value = _ui.value.copy(error = null, savedAt = null)
    }

    data class UiState(
        val saving: Boolean = false,
        val error: String? = null,
        val savedAt: Long? = null
    )
}
