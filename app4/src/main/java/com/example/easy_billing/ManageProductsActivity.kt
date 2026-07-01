package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.viewmodel.ManageProductsViewModel
import com.example.easy_billing.viewmodel.ManageProductsViewModel.Filter
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * Browse + search + filter the local product catalogue, then jump
 * into [EditProductActivity] for any row.
 *
 * This activity is read-only — every actual mutation happens in the
 * Edit screen so the responsibilities stay cleanly separated.
 */
class ManageProductsActivity : BaseActivity() {

    private val viewModel: ManageProductsViewModel by viewModels()

    private lateinit var rv: RecyclerView
    private lateinit var adapter: ManageProductsAdapter
    private lateinit var etSearch: TextInputEditText
    private lateinit var chipGroup: ChipGroup
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_products)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = getString(R.string.manage_products)

        bindViews()
        setupRecycler()
        wireFilters()
        observe()
    }

    override fun onResume() {
        super.onResume()
        // Edit screen may have changed something — refresh the list.
        viewModel.reload()
    }

    private fun bindViews() {
        rv        = findViewById(R.id.rvProducts)
        etSearch  = findViewById(R.id.etSearch)
        chipGroup = findViewById(R.id.chipFilter)
        tvEmpty   = findViewById(R.id.tvEmpty)
    }

    private fun setupRecycler() {
        adapter = ManageProductsAdapter { product ->
            startActivity(
                Intent(this, EditProductActivity::class.java)
                    .putExtra(EditProductActivity.EXTRA_PRODUCT_ID, product.id)
            )
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
    }

    private fun wireFilters() {
        etSearch.addTextChangedListener { editable ->
            viewModel.setQuery(editable?.toString().orEmpty())
        }

        chipGroup.setOnCheckedStateChangeListener { _, checked ->
            val id = checked.firstOrNull() ?: return@setOnCheckedStateChangeListener
            viewModel.setFilter(
                when (id) {
                    R.id.chipPurchased -> Filter.PURCHASED
                    R.id.chipManual    -> Filter.MANUAL
                    else               -> Filter.ALL
                }
            )
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filtered.collect { list ->
                    adapter.submit(list)
                    tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }
}
