package com.example.easy_billing.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
 * Holds the loaded shop-scoped product list plus the search query
 * and a single-select filter (All / Purchased / Manual). The
 * derived [filtered] flow combines all three so the UI just
 * subscribes once.
 */
class ManageProductsViewModel(app: Application) : AndroidViewModel(app) {

    enum class Filter { ALL, PURCHASED, MANUAL }

    private val productRepo = ProductRepository.get(app)

    private val _all = MutableStateFlow<List<Product>>(emptyList())
    val all: StateFlow<List<Product>> = _all.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    val filtered: StateFlow<List<Product>> =
        combine(_all, _query, _filter) { list, q, f ->
            val byFilter = when (f) {
                Filter.ALL       -> list
                Filter.PURCHASED -> list.filter { it.isPurchased }
                Filter.MANUAL    -> list.filter { !it.isPurchased }
            }
            if (q.isBlank()) byFilter
            else byFilter.filter {
                it.name.contains(q, ignoreCase = true) ||
                it.variant?.contains(q, ignoreCase = true) == true ||
                it.hsnCode?.contains(q, ignoreCase = true) == true
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
        }
    }

    fun setQuery(q: String) { _query.value = q }
    fun setFilter(f: Filter) { _filter.value = f }
}
