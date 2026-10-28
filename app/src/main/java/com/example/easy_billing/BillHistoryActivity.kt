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
import retrofit2.HttpException
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
    private lateinit var tvOnCreditToday: TextView
    private lateinit var tvCashToday: TextView

    private lateinit var btnFilter: android.view.View
    private lateinit var btnSort: android.view.View
    private lateinit var tvFilterBadge: TextView
    private lateinit var tvResultSummary: TextView
    private lateinit var btnResetFilters: android.view.View
    private lateinit var tipCreateBill: android.view.View
    private lateinit var btnCreateBill: android.view.View

    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private var allBills: List<BillResponse> = emptyList()
    private var dueInfo: Map<Int, DueSummary> = emptyMap()
    private var summaryJob: kotlinx.coroutines.Job? = null

    // Filter (date range / cancelled) and sort are independent dimensions —
    // both apply together, rather than one mutually-exclusive chip value.
    private val filterKeys = listOf("ALL", "TODAY", "WEEK", "MONTH", "CANCELLED", "DUES", "PENDING")
    private val filterLabels by lazy {
        listOf(
            getString(R.string.bill_history_filter_all_label),
            getString(R.string.bill_history_filter_today_label),
            getString(R.string.bill_history_filter_week_label),
            getString(R.string.bill_history_filter_month_label),
            getString(R.string.bill_history_filter_cancelled_label),
            getString(R.string.bill_history_filter_dues_label),
            getString(R.string.bill_history_filter_pending_label)
        )
    }
    private var activeFilter: String = "ALL"

    // Trimmed from five sort options to the two people actually use —
    // amount-high/low and bill-number sorting went unused and just added
    // noise to the dropdown (same simplification as Purchase History).
    private val sortKeys = listOf("NEWEST", "OLDEST")
    private val sortLabels by lazy {
        listOf(
            getString(R.string.sort_newest_first),
            getString(R.string.sort_oldest_first)
        )
    }
    private var activeSort: String = "NEWEST"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_history)
        com.example.easy_billing.util.UserEventLogger.logAction("BillHistory", "opened")

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        initViews()
        setupRecycler()
        setupSearch()
        setupFilterAndSort()

        loadBills()
    }

    // Re-fetches every time this screen comes back into view — e.g.
    // after marking a UPI bill as paid (or cancelling one) inside
    // BillDetailsActivity and pressing back. Cheap enough (one GET) to
    // just always do this rather than thread a "did anything change"
    // result back through the Activity result API.
    private var hasLoadedOnce = false
    override fun onResume() {
        super.onResume()
        if (hasLoadedOnce) loadBills()
        hasLoadedOnce = true
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
        tvOnCreditToday = findViewById(R.id.tvOnCreditToday)
        tvCashToday = findViewById(R.id.tvCashToday)

        btnFilter = findViewById(R.id.btnFilter)
        btnSort = findViewById(R.id.btnSort)
        tvFilterBadge = findViewById(R.id.tvFilterBadge)
        tvResultSummary = findViewById(R.id.tvResultSummary)
        btnResetFilters = findViewById(R.id.btnResetFilters)
        tipCreateBill = findViewById(R.id.tipCreateBill)
        btnCreateBill = findViewById(R.id.btnCreateBill)
    }

// ================= RECYCLER =================

    private fun setupRecycler() {
        rvBills.layoutManager = LinearLayoutManager(this)

        adapter = BillHistoryAdapter { bill ->
            val intent = Intent(this, BillDetailsActivity::class.java)
            if (bill.local_id != null) {
                // On this phone: open from phone data (works with no internet).
                intent.putExtra("LOCAL_BILL_ID", bill.local_id)
                intent.putExtra("BILL_NUMBER", bill.bill_number)
            } else {
                intent.putExtra("BILL_ID", bill.bill_id)
            }
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

            // Phone data first: every bill saved on this phone is listed even with no
            // internet. The server list only adds bills this phone does not have.
            var serverFailure: Exception? = null
            try {
                val db = com.example.easy_billing.db.AppDatabase.getDatabase(this@BillHistoryActivity)

                // Server list first, phone list second: a bill that syncs in between is then
                // already numbered on the phone, so it matches by number and is not shown twice.
                var serverAll: List<BillResponse> = emptyList()
                if (isInternetAvailable()) {
                    try {
                        serverAll = RetrofitClient.api.getBills(token)
                    } catch (e: Exception) {
                        serverFailure = e
                    }
                }

                val localRows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    db.billDao().getAllBills()
                }
                // Open "money still due" notes, grouped by bill, for the row tags and the Dues filter.
                // First make sure every cancelled bill that still holds received money has its refund note
                // (repairs a bill cancelled before the note was saved); a bill with nothing to fix is untouched.
                val dueRows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    for (cb in localRows) {
                        if (!cb.isCancelled) continue
                        try { com.example.easy_billing.util.CancelMoney.ensureRefundNote(db, cb.id) } catch (_: Exception) { }
                    }
                    db.moneyEntryDao().openBillDues()
                }
                dueInfo = dueRows.groupBy { it.parentId }.mapValues { (_, rows) ->
                    DueSummary(
                        refund = rows.filter { it.direction == com.example.easy_billing.util.MoneyDirection.OUT }.sumOf { it.amount },
                        extra = rows.filter { it.direction == com.example.easy_billing.util.MoneyDirection.IN }.sumOf { it.amount },
                        late = rows.any {
                            it.direction == com.example.easy_billing.util.MoneyDirection.OUT &&
                                it.sourceType == com.example.easy_billing.util.MoneySource.LATE_PAYMENT
                        }
                    )
                }
                adapter.setDueInfo(dueInfo)
                val localList = localRows.map { b ->
                    BillResponse(
                        bill_id = b.id,
                        bill_number = b.billNumber,
                        total_amount = b.total,
                        gst = b.gst,
                        discount = b.discount,
                        payment_method = b.paymentMethod,
                        created_at = b.date,
                        invoice_type = b.customerType,
                        customer_state_code = b.placeOfSupply.ifBlank { null },
                        supply_type = b.supplyType,
                        is_cancelled = b.isCancelled,
                        payment_status = b.paymentStatus,
                        local_id = b.id
                    )
                }
                val localNumbers = localList.map { it.bill_number }.filter { it.isNotBlank() }.toHashSet()
                val serverOnly = serverAll.filter { it.bill_number !in localNumbers }

                // ✅ Sort latest first (server may write "T" between date and time)
                val sorted = (localList + serverOnly)
                    .sortedByDescending { it.created_at.replace('T', ' ').take(19) }

                val failure = serverFailure
                if (failure != null && sorted.isEmpty()) throw failure

                allBills = sorted
                adapter.submitList(sorted)
                updateSummary(sorted)
                showListState(sorted)
                updateFilterSortIndicators(sorted.size)

            } catch (e: Exception) {
                e.printStackTrace()
                // Temporary diagnostic: capture the exact HTTP status/body so
                // we can tell a device-binding/auth rejection (401/403) apart
                // from a generic network hiccup instead of guessing blind —
                // remove this extra detail once the real cause is confirmed.
                val detail = if (e is HttpException) {
                    val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                    "HTTP ${e.code()}${if (!body.isNullOrBlank()) ": $body".take(200) else ""}"
                } else {
                    e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                }
                com.example.easy_billing.util.UserEventLogger.logError(
                    "BillHistory", "load_bills_failed: $detail"
                )
                // Distinguish "no connection" from a genuine server error so
                // the shop owner knows whether to check their internet or
                // just retry — a flat "Failed to load bills" gave no hint.
                val message = if (!isInternetAvailable())
                    getString(R.string.bill_history_offline_note)
                else
                    getString(R.string.bill_history_load_failed) + " ($detail)"
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
        // Bills are created from the dashboard (add items, then checkout),
        // not from a standalone screen, so both entry points here just
        // return to it rather than opening something new.
        tipCreateBill.setOnClickListener { finish() }
        btnCreateBill.setOnClickListener { finish() }

        btnFilter.setOnClickListener { showFilterPopup() }
        btnSort.setOnClickListener { showSortPopup() }
        btnResetFilters.setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("BillHistory", "reset_filters_clicked")
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
            com.example.easy_billing.util.UserEventLogger.logAction("BillHistory", "filter_changed: $picked")
            refreshList()
        }
    }

    private fun showSortPopup() {
        showOptionsPopup(btnSort, sortKeys, sortLabels, activeSort) { picked ->
            activeSort = picked
            com.example.easy_billing.util.UserEventLogger.logAction("BillHistory", "sort_changed: $picked")
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
        val countWord = if (resultCount == 1)
            getString(R.string.bill_history_result_count_one)
        else
            getString(R.string.bill_history_result_count_other)
        tvResultSummary.text = "$resultCount $countWord · $sortLabel"
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

            // Bills that still need a refund handed back or an extra charge collected.
            "DUES" -> source.filter { val id = it.local_id; id != null && dueInfo.containsKey(id) }

            // UPI money not received yet: the bills where "Mark as paid" or a payment is still waiting.
            "PENDING" -> source.filter {
                !it.is_cancelled && com.example.easy_billing.util.PaymentMethodText.hasUpi(it.payment_method) &&
                    it.payment_status != "paid"
            }

            else -> source
        }

        return when (activeSort) {
            "OLDEST" -> filtered.sortedBy { it.created_at }
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

        tvTodaySales.text = CurrencyHelper.format(this, todayBills.sumOf { it.total_amount })
        tvBillsToday.text = todayBills.size.toString()

        // "Received" = money actually kept from today's bills, straight from the Money Book:
        // UPI only once it has arrived, credit never, and refunds already handed back taken off.
        // "On credit" = what those bills still have as debt (returns, extra charges and the
        // customer's payments counted), the same figure the bill's own "Still owed" banner shows.
        summaryJob?.cancel()
        summaryJob = lifecycleScope.launch {
            val (sums, unknownSplits) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val db = com.example.easy_billing.db.AppDatabase.getDatabase(this@BillHistoryActivity)
                val shop = getSharedPreferences("auth", MODE_PRIVATE).getInt("SHOP_ID", -1)
                val money = com.example.easy_billing.repository.MoneyBook(db.moneyEntryDao())
                val pm = com.example.easy_billing.util.PaymentMethodText
                var receivedSum = 0.0
                var creditSum = 0.0
                var unknownSplits = 0
                val creditBills = mutableListOf<com.example.easy_billing.db.Bill>()
                for (b in todayBills) {
                    val id = b.local_id
                    var local: com.example.easy_billing.db.Bill? = null
                    if (id != null) {
                        local = try { db.billDao().getBillById(id) } catch (_: Exception) { null }
                    }
                    if (local == null) {
                        // Only on the server: no Money Book rows here, so go by the payment method.
                        if (!pm.hasCredit(b.payment_method) && !(pm.hasUpi(b.payment_method) && b.payment_status != "paid")) {
                            receivedSum += b.total_amount
                        } else if (pm.hasCredit(b.payment_method) && !pm.isSplit(b.payment_method)) {
                            creditSum += b.total_amount
                        } else if (pm.isSplit(b.payment_method)) {
                            // A split bill that is only on the server: how much of it was paid or is on credit is not known here.
                            unknownSplits++
                        }
                        continue
                    }
                    if (db.moneyEntryDao().forParent(com.example.easy_billing.util.MoneySource.BILL, local.id).isEmpty()) {
                        // Older bill with no Money Book rows: go by the payment method.
                        if (!pm.hasCredit(local.paymentMethod) && !(pm.hasUpi(local.paymentMethod) && local.paymentStatus != "paid")) {
                            receivedSum += local.total
                        }
                    } else {
                        receivedSum += money.refundCeiling(
                            com.example.easy_billing.util.MoneySource.BILL, local.id, com.example.easy_billing.util.MoneyDirection.IN
                        )
                    }
                    if (pm.hasCredit(local.paymentMethod)) creditBills += local
                }
                creditSum += com.example.easy_billing.util.BillCredit.owedForBills(db, creditBills, shop)
                (receivedSum to creditSum) to unknownSplits
            }
            val (received, credit) = sums
            // A trailing "+" says "at least this much": some split bills are not on this phone, so their parts are not counted.
            val more = if (unknownSplits > 0) "+" else ""
            tvCashToday.text = CurrencyHelper.format(this@BillHistoryActivity, received) + more
            tvOnCreditToday.text = CurrencyHelper.format(this@BillHistoryActivity, credit) + more
        }
    }
}
