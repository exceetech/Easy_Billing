package com.example.easy_billing

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
 * Filter/Sort icon buttons (same pattern as BillHistoryActivity) over the
 * recorded purchase invoices. Tapping a row opens [PurchaseDetailsActivity].
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

    private lateinit var btnFilter: View
    private lateinit var btnSort: View
    private lateinit var tvFilterBadge: TextView
    private lateinit var tvResultSummary: TextView
    private lateinit var btnResetFilters: View

    // Filter (status) and sort are independent dimensions — both apply
    // together, same as BillHistoryActivity.
    private val filterKeys = listOf("ALL", "CREDIT", "CASH", "CANCELLED")
    private var filterLabels = listOf("All purchases", "Credit", "Cash", "Cancelled")
    private var activeFilter = "ALL"

    private val sortKeys = listOf("NEWEST", "OLDEST", "AMOUNT_HIGH", "AMOUNT_LOW", "SUPPLIER")
    private val sortLabels = listOf("Newest first", "Oldest first", "Highest amount", "Lowest amount", "Supplier name")
    private var activeSort = "NEWEST"

    private var allPurchases: List<Purchase> = emptyList()
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

        btnFilter        = findViewById(R.id.btnFilter)
        btnSort          = findViewById(R.id.btnSort)
        tvFilterBadge    = findViewById(R.id.tvFilterBadge)
        tvResultSummary  = findViewById(R.id.tvResultSummary)
        btnResetFilters  = findViewById(R.id.btnResetFilters)

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

        btnFilter.setOnClickListener { showFilterPopup() }
        btnSort.setOnClickListener { showSortPopup() }
        btnResetFilters.setOnClickListener {
            activeFilter = "ALL"
            activeSort = "NEWEST"
            applyFilter()
        }

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

        // Counts ride along in the filter popup's labels rather than on a
        // chip, since the chip row is gone.
        filterLabels = listOf(
            "All purchases (${s.countAll})",
            "Credit (${s.countCredit})",
            "Cash (${s.countCash})",
            "Cancelled (${s.countCancelled})"
        )
    }

// ================= FILTER + SORT =================

    /** Champagne dropdown popup — same build-a-PopupWindow pattern as
     * BillHistoryActivity.showOptionsPopup / ManageProductsActivity.showSortPopup. */
    private fun showOptionsPopup(
        anchor: View,
        keys: List<String>,
        labels: List<String>,
        current: String,
        onPick: (String) -> Unit
    ) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val accent = Color.parseColor("#0F6E56")
        val ink = Color.parseColor("#1A1A18")
        val medium = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.googlesans_medium)
        val currentIndex = keys.indexOf(current).coerceAtLeast(0)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        val popup = android.widget.PopupWindow(
            scroll, dp(230),
            minOf(labels.size * dp(44) + dp(10), dp(360)),
            true
        ).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        labels.forEachIndexed { i, label ->
            val isSel = i == currentIndex
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                typeface = medium
                setTextColor(if (isSel) accent else ink)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)
            if (isSel) {
                row.addView(android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(accent)
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(16), dp(16))
                })
            }
            row.setOnClickListener {
                onPick(keys[i])
                popup.dismiss()
            }
            container.addView(row)
        }

        popup.showAsDropDown(anchor, 0, dp(6))
    }

    private fun showFilterPopup() {
        showOptionsPopup(btnFilter, filterKeys, filterLabels, activeFilter) { picked ->
            activeFilter = picked
            applyFilter()
        }
    }

    private fun showSortPopup() {
        showOptionsPopup(btnSort, sortKeys, sortLabels, activeSort) { picked ->
            activeSort = picked
            applyFilter()
        }
    }

    private fun applyFilter() {
        val byStatus = allPurchases.filter { p ->
            when (activeFilter) {
                "CREDIT"    -> p.isCredit && !p.isCancelled
                "CASH"      -> !p.isCredit && !p.isCancelled
                "CANCELLED" -> p.isCancelled
                else -> true // ALL
            }
        }
        val bySearch = if (query.isEmpty()) byStatus else byStatus.filter {
            it.supplierName.contains(query, true) || it.invoiceNumber.contains(query, true)
        }
        val result = when (activeSort) {
            "OLDEST" -> bySearch.sortedBy { it.createdAt }
            "AMOUNT_HIGH" -> bySearch.sortedByDescending { it.invoiceValue }
            "AMOUNT_LOW" -> bySearch.sortedBy { it.invoiceValue }
            "SUPPLIER" -> bySearch.sortedBy { it.supplierName.lowercase() }
            else -> bySearch.sortedByDescending { it.createdAt } // NEWEST
        }

        adapter.update(result)
        tvEmpty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
        updateFilterSortIndicators(result.size)
    }

    /** Filter icon gets a small red count badge whenever a non-default
     * filter is active; the result pill always shows "N purchases · sort
     * label", and Reset only appears once something's non-default. */
    private fun updateFilterSortIndicators(resultCount: Int) {
        val hasActiveFilter = activeFilter != "ALL"
        val hasActiveSort = activeSort != "NEWEST"

        tvFilterBadge.visibility = if (hasActiveFilter) View.VISIBLE else View.GONE
        btnResetFilters.visibility = if (hasActiveFilter || hasActiveSort) View.VISIBLE else View.GONE

        val sortLabel = sortLabels[sortKeys.indexOf(activeSort)]
        tvResultSummary.text = "$resultCount purchase${if (resultCount == 1) "" else "s"} · $sortLabel"
    }
}
