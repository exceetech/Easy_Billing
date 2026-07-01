package com.example.easy_billing.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.easy_billing.db.Product
import com.example.easy_billing.repository.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs [com.example.easy_billing.AddProductsActivity] /
 * `dialog_add_product.xml`.
 *
 *   • [autofill] — looks up the local shop_product table by name
 *     OR HSN and pushes the result into [autofillResult] for the
 *     dialog to consume.
 *   • [save] — capitalises name + variant, upserts into the local
 *     product table.
 */
class AddProductViewModel(app: Application) : AndroidViewModel(app) {

    private val productRepo = ProductRepository.get(app)

    private val _autofillResult = MutableStateFlow<Product?>(null)
    val autofillResult: StateFlow<Product?> = _autofillResult.asStateFlow()

    private val _names = MutableStateFlow<List<String>>(emptyList())
    val names: StateFlow<List<String>> = _names.asStateFlow()

    private val _variants = MutableStateFlow<List<String>>(emptyList())
    val variants: StateFlow<List<String>> = _variants.asStateFlow()

    init {
        loadCatalog()
    }

    fun loadCatalog() {
        viewModelScope.launch {
            _names.value = productRepo.distinctNames()
            _variants.value = productRepo.distinctVariants()
        }
    }

    /**
     * Called from a TextWatcher on the product-name OR HSN field.
     * Emits a hit on [autofillResult] so the UI can populate HSN +
     * tax inputs from history.
     */
    fun autofill(name: String? = null, hsn: String? = null) {
        viewModelScope.launch {
            _autofillResult.value = productRepo.autoFillFromHistory(name, hsn)
        }
    }

    fun clearAutofill() { _autofillResult.value = null }

    suspend fun save(product: Product): Int = productRepo.upsert(product)
}
