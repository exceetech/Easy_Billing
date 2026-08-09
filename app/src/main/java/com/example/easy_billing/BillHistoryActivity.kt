package com.example.easy_billing

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.BillResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.CurrencyHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class BillHistoryActivity : BaseActivity() {
    private lateinit var rvBills: RecyclerView
    private lateinit var cardBillHistory: android.view.View
    private lateinit var progressBills: android.widget.ProgressBar
    private lateinit var layoutBillsEmpty: android.view.View
    private lateinit var tvBillsEmptyTitle: TextView
    private lateinit var tvBillsEmptyTitleAccent: TextView
    private lateinit var tvBillsEmptyBody: TextView
    private lateinit var adapter: BillHistoryAdapter
    private lateinit var etSearch: EditText

    private lateinit var tvTodaySales: TextView
    private lateinit var tvBillsToday: TextView

    private lateinit var btnFilter: android.view.View
    private lateinit var btnSort: android.view.View
    private lateinit var tvFilterBadge: TextView
    private lateinit var tvResultSummary: TextView
    private lateinit var btnResetFilters: android.view.View

    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private var allBills: List<BillResponse> = emptyList()

    // Filter (date range / cancelled) and sort are independent dimensions —
    // both apply together, rather than one mutually-exclusive chip value.
    private val filterKeys = listOf("ALL", "TODAY", "WEEK", "MONTH", "CANCELLED")
    private val filterLabels = listOf("All bills", "Today", "This week", "This month", "Cancelled")
    private var activeFilter: String = "ALL"

    private val sortKeys = listOf("NEWEST", "OLDEST", "AMOUNT_HIGH", "AMOUNT_LOW", "BILL_NUMBER")
    private val sortLabels = listOf("Newest first", "Oldest first", "Highest amount", "Lowest amount", "Bill number")
    private var activeSort: String = "NEWEST"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_history)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        initViews()
        setupRecycler()
        setupSearch()
        setupFilterAndSort()

        loadBills()
    }

// ================= INIT =================

    private fun initViews() {
        rvBills = findViewById(R.id.rvBills)
        cardBillHistory = findViewById(R.id.cardBillHistory)
        progressBills = findViewById(R.id.progressBills)
        layoutBillsEmpty = findViewById(R.id.layoutBillsEmpty)
        tvBillsEmptyTitle = findViewById(R.id.tvBillsEmptyTitle)
        tvBillsEmptyTitleAccent = findViewById(R.id.tvBillsEmptyTitleAccent)
        tvBillsEmptyBody = findViewById(R.id.tvBillsEmptyBody)
        etSearch = findViewById(R.id.etSearch)

        tvTodaySales = findViewById(R.id.tvTodaySales)
        tvBillsToday = findViewById(R.id.tvBillsToday)

        btnFilter = findViewById(R.id.btnFilter)
        btnSort = findViewById(R.id.btnSort)
        tvFilterBadge = findViewById(R.id.tvFilterBadge)
        tvResultSummary = findViewById(R.id.tvResultSummary)
        btnResetFilters = findViewById(R.id.btnResetFilters)
    }

// ================= RECYCLER =================

    private fun setupRecycler() {
        rvBills.layoutManager = LinearLayoutManager(this)

        adapter = BillHistoryAdapter { bill ->
            val intent = Intent(this, BillDetailsActivity::class.java)
            intent.putExtra("BILL_ID", bill.bill_id)
            startActivity(intent)
        }

        rvBills.adapter = adapter

        // Applied to the shared card container rather than rvBills directly
        // — the rounded background lives on cardBillHistory now, so that's
        // the view whose outline actually clips the row corners.
        cardBillHistory.clipToOutline = true
    }

// ================= LOAD =================

    private fun loadBills() {
        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            if (token == null) {
                Toast.makeText(this@BillHistoryActivity, R.string.bill_history_not_logged_in, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }

            progressBills.visibility = android.view.View.VISIBLE
            layoutBillsEmpty.visibility = android.view.View.GONE

            try {
                val bills = RetrofitClient.api.getBills(token)

                // ✅ Sort latest first
                val sorted = bills.sortedByDescending { it.created_at }

                allBills = sorted
                adapter.submitList(sorted)
                updateSummary(sorted)
                showListState(sorted)
                updateFilterSortIndicators(sorted.size)

            } catch (e: Exception) {
                e.printStackTrace()
                // Distinguish "no connection" from a genuine server error so
                // the shop owner knows whether to check their internet or
                // just retry — a flat "Failed to load bills" gave no hint.
                val message = if (!isInternetAvailable())
                    getString(R.string.bill_history_offline_note)
                else
                    getString(R.string.bill_history_load_failed)
                Toast.makeText(this@BillHistoryActivity, message, Toast.LENGTH_LONG).show()
                showListState(allBills)
            } finally {
                progressBills.visibility = android.view.View.GONE
            }
        }
    }

    private fun showListState(bills: List<BillResponse>) {
        layoutBillsEmpty.visibility = if (bills.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        rvBills.visibility = if (bills.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Switches the empty state between "nothing recorded at all" and "search/filter found
     *  nothing" — same card, same design, just different copy for the two situations. */
    private fun setEmptyStateForSearch(isSearchOrFilter: Boolean) {
        if (isSearchOrFilter) {
            tvBillsEmptyTitle.text = getString(R.string.credit_no_matches_title)
            tvBillsEmptyTitleAccent.text = getString(R.string.credit_no_matches_accent)
            tvBillsEmptyBody.text = getString(R.string.bill_history_no_matches_body)
        } else {
            tvBillsEmptyTitle.text = getString(R.string.bill_history_no_bills_title)
            tvBillsEmptyTitleAccent.text = getString(R.string.credit_no_customers_accent)
            tvBillsEmptyBody.text = getString(R.string.bill_history_no_bills_body)
        }
    }

// ================= SEARCH =================

    private fun setupSearch() {
        etSearch.clearFocus()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {

                val query = text?.toString()?.trim()?.lowercase() ?: ""
                adapter.setSearchQuery(query)

                searchRunnable?.let { handler.removeCallbacks(it) }

                searchRunnable = Runnable {

                    val baseList = applyFilterAndSort(allBills)

                    if (query.isEmpty()) {
                        adapter.submitList(baseList)
                        setEmptyStateForSearch(isSearchOrFilter = false)
                        showListState(baseList)
                        updateFilterSortIndicators(baseList.size)
                        return@Runnable
                    }

                    val filtered = baseList.filter { bill ->
                        bill.bill_number.contains(query, true) ||
                                bill.payment_method.contains(query, true) ||
                                bill.total_amount.toString().contains(query) ||
                                bill.created_at.contains(query, true)
                    }

                    adapter.submitList(filtered)
                    setEmptyStateForSearch(isSearchOrFilter = true)
                    showListState(filtered)
                    updateFilterSortIndicators(filtered.size)
                }

                handler.postDelayed(searchRunnable!!, 300)
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

// ================= FILTER + SORT =================

    private fun setupFilterAndSort() {
        btnFilter.setOnClickListener { showFilterPopup() }
        btnSort.setOnClickListener { showSortPopup() }
        btnResetFilters.setOnClickListener {
            activeFilter = "ALL"
            activeSort = "NEWEST"
            refreshList()
        }
    }

    /** Champagne dropdown popup — same build-a-PopupWindow pattern as
     * ManageProductsActivity.showSortPopup, reused here for both the filter
     * and sort buttons since they're the same "pick one of these options"
     * shape. */
    private fun showOptionsPopup(
        anchor: android.view.View,
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
            scroll, dp(220),
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
            refreshList()
        }
    }

    private fun showSortPopup() {
        showOptionsPopup(btnSort, sortKeys, sortLabels, activeSort) { picked ->
            activeSort = picked
            refreshList()
        }
    }

    private fun refreshList() {
        val result = applyFilterAndSort(allBills)
        adapter.submitList(result)
        updateSummary(result)
        // A non-default filter is a form of "search" here — same empty-state
        // copy as a text search that came up empty, since both mean "the
        // filter/query hides everything, not that nothing was ever billed".
        setEmptyStateForSearch(isSearchOrFilter = activeFilter != "ALL")
        showListState(result)
        updateFilterSortIndicators(result.size)
    }

    /** Filter icon gets a small red count badge whenever a non-default
     * filter is active; the result pill always shows "N bills · sort label"
     * so the current state stays visible without labelled buttons. The
     * "Reset" action only appears once something's actually non-default,
     * so there's a one-tap way back to All bills / Newest first. */
    private fun updateFilterSortIndicators(resultCount: Int) {
        val hasActiveFilter = activeFilter != "ALL"
        val hasActiveSort = activeSort != "NEWEST"

        tvFilterBadge.visibility = if (hasActiveFilter) android.view.View.VISIBLE else android.view.View.GONE
        btnResetFilters.visibility = if (hasActiveFilter || hasActiveSort) android.view.View.VISIBLE else android.view.View.GONE

        val sortLabel = sortLabels[sortKeys.indexOf(activeSort)]
        tvResultSummary.text = "$resultCount bill${if (resultCount == 1) "" else "s"} · $sortLabel"
    }

    private fun applyFilterAndSort(source: List<BillResponse>): List<BillResponse> {

        val today = AppTime.todayIso()   // app timezone (matches backend created_at)

        val filtered = when (activeFilter) {

            "TODAY" -> source.filter {
                it.created_at.startsWith(today)
            }

            "WEEK" -> {
                val cal = AppTime.calendar()
                cal.add(Calendar.DAY_OF_YEAR, -7)

                source.filter {
                    val dateStr = it.created_at.substring(0, 10)
                    val billDate = AppTime.isoDate().parse(dateStr)
                    billDate != null && billDate.after(cal.time)
                }
            }

            "MONTH" -> {
                val cal = AppTime.calendar()
                cal.add(Calendar.MONTH, -1)

                source.filter {
                    val dateStr = it.created_at.substring(0, 10)
                    val billDate = AppTime.isoDate().parse(dateStr)
                    billDate != null && billDate.after(cal.time)
                }
            }

            "CANCELLED" -> source.filter { it.is_cancelled }

            else -> source
        }

        return when (activeSort) {
            "OLDEST" -> filtered.sortedBy { it.created_at }
            "AMOUNT_HIGH" -> filtered.sortedByDescending { it.total_amount }
            "AMOUNT_LOW" -> filtered.sortedBy { it.total_amount }
            "BILL_NUMBER" -> filtered.sortedByDescending { it.bill_number }
            else -> filtered.sortedByDescending { it.created_at } // NEWEST
        }
    }

// ================= SUMMARY =================

    private fun updateSummary(bills: List<BillResponse>) {

        val today = AppTime.todayIso()   // app timezone (matches backend created_at)

        // N1: cancelled bills are visible in the list but must not count
        // in the summary — keeps it consistent with the Reports screen.
        val todayBills = bills.filter {
            !it.is_cancelled && it.created_at.startsWith(today)
        }

        val totalToday = todayBills.sumOf { it.total_amount }

        tvTodaySales.text = CurrencyHelper.format(this, totalToday)
        tvBillsToday.text = todayBills.size.toString()
    }
}
