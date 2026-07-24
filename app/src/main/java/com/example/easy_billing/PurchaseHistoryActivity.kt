package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Purchase
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.viewmodel.PurchaseHistoryViewModel
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Purchase history — champagne / khata language. Summary card, search, and
 * status filter chips over the recorded purchase invoices. Tapping a row opens
 * [PurchaseDetailsActivity].
 */
class PurchaseHistoryActivity : BaseActivity() {

    private val viewModel: PurchaseHistoryViewModel by viewModels()

    private lateinit var rvPurchases: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: PurchaseHistoryAdapter

    private lateinit var tvBought: TextView
    private lateinit var tvBoughtCount: TextView
    private lateinit var tvOnCredit: TextView
    private lateinit var tvCash: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var chipAll: TextView
    private lateinit var chipCredit: TextView
    private lateinit var chipCash: TextView
    private lateinit var chipCancelled: TextView

    private enum class Filter { ALL, CREDIT, CASH, CANCELLED }

    private var allPurchases: List<Purchase> = emptyList()
    private var activeFilter = Filter.ALL
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_purchase_history)
        setupToolbar(R.id.toolbar)

        rvPurchases   = findViewById(R.id.rvPurchases)
        progressBar   = findViewById(R.id.progressBar)
        tvEmpty       = findViewById(R.id.tvEmpty)
        tvBought      = findViewById(R.id.tvBought)
        tvBoughtCount = findViewById(R.id.tvBoughtCount)
        tvOnCredit    = findViewById(R.id.tvOnCredit)
        tvCash        = findViewById(R.id.tvCash)
        etSearch      = findViewById(R.id.etSearch)
        chipAll       = findViewById(R.id.chipAll)
        chipCredit    = findViewById(R.id.chipCredit)
        chipCash      = findViewById(R.id.chipCash)
        chipCancelled = findViewById(R.id.chipCancelled)

        adapter = PurchaseHistoryAdapter(emptyList()) { purchase ->
            startActivity(
                Intent(this, PurchaseDetailsActivity::class.java)
                    .putExtra("PURCHASE_ID", purchase.id)
            )
        }
        rvPurchases.layoutManager = LinearLayoutManager(this)
        rvPurchases.adapter = adapter
        // Let the first and last row follow the card's rounded corners.
        rvPurchases.clipToOutline = true

        chipAll.setOnClickListener { setFilter(Filter.ALL) }
        chipCredit.setOnClickListener { setFilter(Filter.CREDIT) }
        chipCash.setOnClickListener { setFilter(Filter.CASH) }
        chipCancelled.setOnClickListener { setFilter(Filter.CANCELLED) }
        setChipSelection()

        etSearch.addTextChangedListener { text ->
            query = text?.toString()?.trim().orEmpty()
            applyFilter()
        }

        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // Reload on resume so returns / cancellations show immediately.
        viewModel.loadPurchases()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.purchases.collectLatest { list ->
                allPurchases = list
                applyFilter()
            }
        }
        lifecycleScope.launch {
            viewModel.summary.collectLatest { s -> bindSummary(s) }
        }
    }

    private fun bindSummary(s: PurchaseHistoryViewModel.Summary) {
        tvBought.text = CurrencyHelper.format(this, s.boughtThisMonth)
        tvBoughtCount.text = if (s.boughtCount == 1) "1 invoice" else "${s.boughtCount} invoices"
        tvOnCredit.text = CurrencyHelper.format(this, s.onCreditThisMonth)
        tvCash.text = CurrencyHelper.format(this, s.cashThisMonth)

        chipAll.text       = "All ${s.countAll}"
        chipCredit.text    = "Credit ${s.countCredit}"
        chipCash.text      = "Cash ${s.countCash}"
        chipCancelled.text = "Cancelled ${s.countCancelled}"
    }

    private fun setFilter(f: Filter) {
        activeFilter = f
        setChipSelection()
        applyFilter()
    }

    private fun setChipSelection() {
        chipAll.isSelected       = activeFilter == Filter.ALL
        chipCredit.isSelected    = activeFilter == Filter.CREDIT
        chipCash.isSelected      = activeFilter == Filter.CASH
        chipCancelled.isSelected = activeFilter == Filter.CANCELLED
    }

    private fun applyFilter() {
        val byStatus = allPurchases.filter { p ->
            when (activeFilter) {
                Filter.ALL       -> true
                Filter.CREDIT    -> p.isCredit && !p.isCancelled
                Filter.CASH      -> !p.isCredit && !p.isCancelled
                Filter.CANCELLED -> p.isCancelled
            }
        }
        val result = if (query.isEmpty()) byStatus else byStatus.filter {
            it.supplierName.contains(query, true) || it.invoiceNumber.contains(query, true)
        }
        adapter.update(result)
        tvEmpty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
    }
}
