package com.example.easy_billing.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Product
import com.example.easy_billing.repository.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * Backs [com.example.easy_billing.ManageProductsActivity].
 *
 * Holds the loaded shop-scoped product list plus the search query,
 * a single-select filter (All / Purchased / Manual / No GST) and a
 * sort order. Also exposes a productId → current-stock map for the
 * per-row stock pills. The derived [filtered] flow combines query +
 * filter + sort so the UI subscribes once.
 */
class ManageProductsViewModel(app: Application) : AndroidViewModel(app) {

    enum class Filter { ALL, PURCHASED, MANUAL, NOGST }
    enum class SortBy {
        NAME_ASC, NAME_DESC, PRICE_LOW, PRICE_HIGH,
        STOCK_LOW, STOCK_HIGH, STOCK_VALUE, CATEGORY
    }

    private val productRepo = ProductRepository.get(app)
    private val db = AppDatabase.getDatabase(app)

    private val _all = MutableStateFlow<List<Product>>(emptyList())
    val all: StateFlow<List<Product>> = _all.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    private val _category = MutableStateFlow("")
    val category: StateFlow<String> = _category.asStateFlow()

    private val _sort = MutableStateFlow(SortBy.NAME_ASC)
    val sort: StateFlow<SortBy> = _sort.asStateFlow()

    /** productId → current stock (only for inventory-tracked products). */
    private val _stock = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val stock: StateFlow<Map<Int, Double>> = _stock.asStateFlow()

    private val _queryAndCat = combine(_query, _category) { q, c -> Pair(q, c) }

    val filtered: StateFlow<List<Product>> =
        combine(_all, _queryAndCat, _filter, _sort, _stock) { list, qc, f, s, stockMap ->
            val (q, c) = qc
            val byFilter = when (f) {
                Filter.ALL       -> list
                Filter.PURCHASED -> list.filter { it.isPurchased }
                Filter.MANUAL    -> list.filter { !it.isPurchased }
                Filter.NOGST     -> list.filter { gstOf(it) <= 0.0 }
            }
            val byCategory = 
                if (c.isBlank() || c == "All") byFilter
                else byFilter.filter { it.category.equals(c, ignoreCase = true) }
            val byQuery =
                if (q.isBlank()) byCategory
                else byCategory.filter {
                    it.name.contains(q, ignoreCase = true) ||
                    it.variant?.contains(q, ignoreCase = true) == true ||
                    it.hsnCode?.contains(q, ignoreCase = true) == true
                }
            when (s) {
                SortBy.NAME_ASC   -> byQuery.sortedBy { it.name.lowercase() }
                SortBy.NAME_DESC  -> byQuery.sortedByDescending { it.name.lowercase() }
                SortBy.PRICE_LOW  -> byQuery.sortedBy { it.price }
                SortBy.PRICE_HIGH -> byQuery.sortedByDescending { it.price }
                SortBy.STOCK_LOW  -> byQuery.sortedBy { stockMap[it.id] ?: Double.MAX_VALUE }
                SortBy.STOCK_HIGH -> byQuery.sortedByDescending { stockMap[it.id] ?: -1.0 }
                SortBy.STOCK_VALUE-> byQuery.sortedByDescending { (stockMap[it.id] ?: 0.0) * it.price }
                SortBy.CATEGORY   -> byQuery.sortedWith(
                    compareBy({ it.category.lowercase() }, { it.name.lowercase() }))
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _all.value = productRepo.getAllForCurrentShop()
            _stock.value = db.inventoryDao().getAll()
                .associate { it.productId to it.currentStock }
        }
    }

    fun setQuery(q: String) { _query.value = q }
    fun setFilter(f: Filter) { _filter.value = f }
    fun setCategory(c: String) { _category.value = c }
    fun setSort(s: SortBy) { _sort.value = s }

    /** Soft-hide (deactivate) a product locally, then refresh. */
    fun deactivate(productId: Int) {
        viewModelScope.launch {
            db.productDao().deactivate(productId)
            reload()
        }
    }

    companion object {
        fun gstOf(p: Product): Double =
            (p.cgstPercentage + p.sgstPercentage).let { if (it > 0) it else p.igstPercentage }
    }
}
