package com.example.easy_billing

import com.example.easy_billing.R

import android.app.Dialog
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.CurrencyHelper
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.ProductProfitRaw
import com.example.easy_billing.network.ProfitResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.InvoicePdfGenerator
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ProfitActivity : AppCompatActivity() {

    // ================= NEW UI =================
    private lateinit var recyclerView: RecyclerView
    private lateinit var cardProfitProducts: android.view.View
    private lateinit var layoutProfitEmpty: android.view.View
    private lateinit var tvProfitEmptyTitle: TextView
    private lateinit var tvProfitEmptyTitleAccent: TextView
    private lateinit var tvProfitEmptyBody: TextView
    private lateinit var profitAdapter: ProfitAdapter
    private lateinit var etSearch: EditText
    private lateinit var btnChart: ImageButton

    private var fullList: List<ProductProfitRaw> = emptyList()
    private var currentSearchQuery: String = ""

    // ================= EXISTING =================
    private lateinit var btnToday: com.google.android.material.chip.Chip
    private lateinit var btnWeek: com.google.android.material.chip.Chip
    private lateinit var btnMonth: com.google.android.material.chip.Chip
    private lateinit var btnAll: com.google.android.material.chip.Chip
    private lateinit var btnCustom: com.google.android.material.chip.Chip

    private var latestProfitList: List<ProductProfitRaw> = emptyList()
    private var currentFilter = "all"

    private var customStartDate: String? = null
    private var customEndDate: String? = null

    // Deliberately NOT a BaseActivity (avoids BaseActivity's forced landscape
    // re-orientation), so the system bars are hidden locally here instead —
    // same immersive treatment every other screen in the app gets.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    // ================= ON CREATE =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profit)
        com.example.easy_billing.util.UserEventLogger.logAction("Profit", "opened")

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayHomeAsUpEnabled(true)
        }
        // Themed back arrow (matches the rest of the app).
        toolbar.setNavigationIcon(R.drawable.ic_back_arrow)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        setupFilters()
        setupRecycler()
        setupSearch()

        btnChart.setOnClickListener {
            val intent = Intent(this, ProfitChartActivity::class.java)
            intent.putExtra("DATA", ArrayList(fullList))
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnPrint).setOnClickListener {
            showPrintConfirmDialog()
        }

        loadProfit("all")
    }

    // Offline-session-timeout coverage (see SessionTimeoutGuard for why this
    // isn't done via extending BaseActivity instead).
    override fun onResume() {
        super.onResume()
        com.example.easy_billing.util.SessionTimeoutGuard.start(this)
    }

    override fun onPause() {
        super.onPause()
        com.example.easy_billing.util.SessionTimeoutGuard.stop(this)
    }

    // Defensive backstop in case onPause is ever skipped by a future edit —
    // stop() is safe to call even if the guard was already stopped.
    override fun onDestroy() {
        com.example.easy_billing.util.SessionTimeoutGuard.stop(this)
        super.onDestroy()
    }

    // ================= RECYCLER =================

    private fun setupRecycler() {
        recyclerView = findViewById(R.id.rvProducts)
        cardProfitProducts = findViewById(R.id.cardProfitProducts)
        layoutProfitEmpty = findViewById(R.id.layoutProfitEmpty)
        tvProfitEmptyTitle = findViewById(R.id.tvProfitEmptyTitle)
        tvProfitEmptyTitleAccent = findViewById(R.id.tvProfitEmptyTitleAccent)
        tvProfitEmptyBody = findViewById(R.id.tvProfitEmptyBody)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // No ItemDecoration here — item_profit_simple.xml already draws its
        // own hairline divider per row (viewRowDivider), same as Inventory's
        // rows. An extra DividerItemDecoration on top of that was adding a
        // redundant gap between cards.

        profitAdapter = ProfitAdapter { item ->
            showProductDialog(item)
        }

        recyclerView.adapter = profitAdapter

        // Applied to the shared card container rather than the recycler
        // directly, since the rounded background lives on
        // cardProfitProducts now.
        cardProfitProducts.clipToOutline = true

        etSearch = findViewById(R.id.etSearch)
        btnChart = findViewById(R.id.btnChart)
    }

    /** Shows/hides the recycler vs. the empty state, and picks the right copy
     *  for "nothing sold in this period" vs. "search found nothing". Called
     *  after every list update — load, filter change, and search. */
    private fun updateProfitListState(list: List<ProductProfitRaw>) {
        layoutProfitEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        recyclerView.visibility = if (list.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        if (currentSearchQuery.isNotEmpty()) {
            tvProfitEmptyTitle.text = "No matches"
            tvProfitEmptyTitleAccent.text = "found"
            tvProfitEmptyBody.text = "No product matches your search. Try a different name or variant."
        } else {
            tvProfitEmptyTitle.text = "No products"
            tvProfitEmptyTitleAccent.text = "yet"
            tvProfitEmptyBody.text = "Sell something in this period and its revenue, cost and profit will break down here by product."
        }
    }

    // ================= SEARCH =================

    private fun setupSearch() {

        etSearch.addTextChangedListener(object : android.text.TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                val query = s.toString().lowercase()
                currentSearchQuery = query

                val filtered = fullList.filter {
                    it.productName.lowercase().contains(query) ||
                            (it.variant?.lowercase()?.contains(query) ?: false)
                }

                profitAdapter.submitList(filtered)
                updateProfitListState(filtered)
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    // ================= FILTER SETUP =================

    private fun setupFilters() {

        btnToday = findViewById(R.id.btnToday)
        btnWeek = findViewById(R.id.btnWeek)
        btnMonth = findViewById(R.id.btnMonth)
        btnAll = findViewById(R.id.btnAll)
        btnCustom = findViewById(R.id.btnCustom)

        // ChipGroup (singleSelection) handles the checked visual; we just load data.
        btnToday.setOnClickListener { currentFilter = "today"; loadProfit("today") }
        btnWeek.setOnClickListener { currentFilter = "week"; loadProfit("week") }
        btnMonth.setOnClickListener { currentFilter = "month"; loadProfit("month") }
        btnAll.setOnClickListener { currentFilter = "all"; loadProfit("all") }
        btnCustom.setOnClickListener { currentFilter = "custom"; openDatePicker() }

        // Same hashed-accent-per-chip concept as the dashboard's category
        // rail — each chip gets a stable color from this palette instead of
        // every selected chip looking identically gold.
        val chipGroup = findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipDateFilter)
        val chips = listOf(
            btnToday to "Today",
            btnWeek to "Week",
            btnMonth to "Month",
            btnAll to "All",
            btnCustom to "Custom"
        )
        fun refreshChipColors() {
            chips.forEach { (chip, label) -> styleDateChip(chip, chip.isChecked, label) }
        }
        chipGroup.setOnCheckedStateChangeListener { _, _ -> refreshChipColors() }
        refreshChipColors()
    }

    private val dateChipPalette = listOf(
        "#0F6E56", "#B23A3A", "#8A6526", "#185FA5",
        "#534AB7", "#D85A30", "#3B6D11", "#993556"
    )

    private fun dateChipColor(label: String): Int =
        android.graphics.Color.parseColor(
            dateChipPalette[(label.hashCode() and 0x7FFFFFFF) % dateChipPalette.size]
        )

    private fun styleDateChip(chip: com.google.android.material.chip.Chip, selected: Boolean, label: String) {
        val accent = dateChipColor(label)
        val strokeColor = if (selected) accent else android.graphics.Color.parseColor("#E4DCC8")
        val textColor = if (selected) accent else android.graphics.Color.parseColor("#6E6A60")
        chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(strokeColor)
        chip.setTextColor(textColor)
    }

    // ================= DATE PICKER =================

    private fun openDatePicker() {

        val constraints = com.google.android.material.datepicker.CalendarConstraints.Builder()
            .setValidator(
                com.google.android.material.datepicker.DateValidatorPointBackward.now()
            )
            .build()

        val picker = com.google.android.material.datepicker.MaterialDatePicker.Builder
            .dateRangePicker()
            .setTitleText("Select Date Range")
            .setCalendarConstraints(constraints)
            .build()

        picker.show(supportFragmentManager, "DATE")

        picker.addOnPositiveButtonClickListener {

            currentFilter = "custom"

            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

            utc.timeInMillis = it.first
            val startDate = utc.time

            utc.timeInMillis = it.second
            val endDate = utc.time

            val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            format.timeZone = TimeZone.getTimeZone("UTC")

            customStartDate = format.format(startDate)
            customEndDate = format.format(endDate)

            btnCustom.isChecked = true

            loadProfit("custom", customStartDate, customEndDate)
        }
    }

    // ================= API =================

    private fun loadProfit(
        filter: String,
        start: String? = null,
        end: String? = null
    ) {

        lifecycleScope.launch {

            try {

                val token = getSharedPreferences("auth", MODE_PRIVATE)
                    .getString("TOKEN", null)

                if (!token.isNullOrEmpty()) {
                    val response: ProfitResponse =
                        RetrofitClient.api.getProfit(
                            token,
                            filter,
                            start,
                            end
                        )

                    val summary = response.summary
                    val localPurchaseExpense = withContext(Dispatchers.IO) {
                        fetchLocalPurchaseExpense()
                    }
                    val finalExpense = maxOf(summary.expense, localPurchaseExpense)

                    val currencySymbol = CurrencyHelper.getCurrencySymbol(this@ProfitActivity)
                    findViewById<TextView>(R.id.tvRevenue).text = "$currencySymbol${"%.2f".format(summary.revenue)}"
                    findViewById<TextView>(R.id.tvCost).text = "$currencySymbol${"%.2f".format(summary.cost)}"
                    findViewById<TextView>(R.id.tvExpense).text = "$currencySymbol${"%.2f".format(finalExpense)}"

                    // Moving-average redesign, Phase 5: the "Loss" tile now
                    // includes purchase-return gain/loss alongside scrap
                    // loss — both are the same thing economically: shelf
                    // value that didn't come back as revenue. This
                    // keeps the tile consistent with `summary.profit`,
                    // which already nets both out. A negative
                    // purchaseReturnVariance (net gain on returns) reduces
                    // this figure, same as it reduces the headline loss.
                    val combinedLoss = summary.loss + summary.purchaseReturnVariance
                    findViewById<TextView>(R.id.tvLoss).text = "$currencySymbol${"%.2f".format(combinedLoss)}"

                    // Net profit spotlight headline (ink for profit, red for loss).
                    val netTv = findViewById<TextView>(R.id.tvNetProfit)
                    netTv.text = "$currencySymbol${"%.2f".format(summary.profit)}"
                    netTv.setTextColor(
                        Color.parseColor(if (summary.profit < 0) "#A32D2D" else "#1A1A18")
                    )
                    val layoutMarginPill = findViewById<android.view.View>(R.id.layoutMarginPill)
                    if (summary.growth != null) {
                        layoutMarginPill.visibility = android.view.View.VISIBLE
                        val growthPct = summary.growth.profit_percentage
                        val positive = growthPct >= 0
                        val pillFg = if (positive) "#085041" else "#791F1F"
                        val sign = if (positive) "+" else ""

                        findViewById<TextView>(R.id.tvMargin).apply {
                            text = "${sign}${Math.round(growthPct)}% growth"
                            setTextColor(Color.parseColor(pillFg))
                        }
                        layoutMarginPill.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(
                                Color.parseColor(if (positive) "#DDEEEE" else "#FBEDED")
                            )
                        findViewById<ImageView>(R.id.ivMarginTrend).apply {
                            setColorFilter(Color.parseColor(pillFg))
                            setImageResource(
                                if (positive) R.drawable.ic_si_trend_up
                                else R.drawable.ic_si_trend_down
                            )
                        }
                    } else {
                        layoutMarginPill.visibility = android.view.View.GONE
                    }

                    val mapped = response.products.map {

                        ProductProfitRaw(
                            productName = it.product_name,
                            variant = it.variant,
                            unit = it.unit,
                            totalQty = it.qty,
                            revenue = it.revenue,
                            cost = it.cost,
                            profit = it.profit,
                            added = it.added,
                            sold = it.sold,
                            remaining = it.remaining,
                            lossQty = it.lossQty,
                            lossAmount = it.lossAmount
                        )
                    }

                    latestProfitList = mapped
                    fullList = mapped
                    currentSearchQuery = ""
                    profitAdapter.submitList(mapped)
                    updateProfitListState(mapped)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runCatching {
                    val localPurchaseExpense = withContext(Dispatchers.IO) {
                        fetchLocalPurchaseExpense()
                    }
                    val currencySymbol = CurrencyHelper.getCurrencySymbol(this@ProfitActivity)
                    findViewById<TextView>(R.id.tvExpense).text = "$currencySymbol${"%.2f".format(localPurchaseExpense)}"
                }
            }
        }
    }

    private suspend fun fetchLocalPurchaseExpense(): Double {
        val db = AppDatabase.getDatabase(this@ProfitActivity)
        val cal = AppTime.calendar()

        val todayStart = cal.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val todayEnd = cal.apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        return when (currentFilter) {
            "today" -> db.purchaseDao().getTotalExpenseBetween(todayStart, todayEnd)
            "week" -> {
                val startCal = AppTime.calendar().apply {
                    set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                db.purchaseDao().getTotalExpenseBetween(startCal.timeInMillis, todayEnd)
            }
            "month" -> {
                val startCal = AppTime.calendar().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                db.purchaseDao().getTotalExpenseBetween(startCal.timeInMillis, todayEnd)
            }
            "year" -> {
                val startCal = AppTime.calendar().apply {
                    set(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                db.purchaseDao().getTotalExpenseBetween(startCal.timeInMillis, todayEnd)
            }
            "custom" -> {
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val startMs = customStartDate?.let { runCatching { format.parse(it)?.time }.getOrNull() } ?: 0L
                val endMs = customEndDate?.let { runCatching { (format.parse(it)?.time ?: 0L) + 86399999L }.getOrNull() } ?: System.currentTimeMillis()
                db.purchaseDao().getTotalExpenseBetween(startMs, endMs)
            }
            else -> db.purchaseDao().getTotalExpenseAll()
        }
    }


    // ================= POPUP =================

    private fun showProductDialog(item: ProductProfitRaw) {

        val view = layoutInflater.inflate(R.layout.dialog_product_detail, null)
        // Centered popup (not a draggable sheet) so it can't be partly hidden.
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun qtyFormat(q: Double) = if (q % 1 == 0.0) q.toInt().toString() else "%.2f".format(q)
        val currencySymbol = CurrencyHelper.getCurrencySymbol(this)
        fun money(v: Double) = "$currencySymbol%,.2f".format(v)

        val name = if (item.variant.isNullOrBlank()) item.productName
            else "${item.productName} (${item.variant})"
        val netProfit = item.profit - item.lossAmount
        val netPositive = netProfit >= 0

        // ---- Header ----
        view.findViewById<TextView>(R.id.tvName).text = name
        val insight = getInsight(item, netProfit)
        view.findViewById<TextView>(R.id.tvNameSub).text =
            "${qtyFormat(item.sold)} sold · ${item.unit} · $insight"

        // ---- Reconciliation card → net profit hero ----
        view.findViewById<TextView>(R.id.tvRevenue).text = money(item.revenue)
        view.findViewById<TextView>(R.id.tvCost).text = money(item.cost)
        view.findViewById<TextView>(R.id.tvLoss).text = money(item.lossAmount)
        view.findViewById<TextView>(R.id.tvLossCaption).text =
            if (item.lossAmount > 0) "adds to loss" else "no shrinkage"

        view.findViewById<TextView>(R.id.tvNetProfit).apply {
            text = money(netProfit)
            setTextColor(Color.parseColor(if (netPositive) "#0F6E56" else "#A32D2D"))
        }
        val margin = if (item.revenue != 0.0) item.profit / item.revenue * 100 else 0.0
        val pillFg = if (netPositive) "#085041" else "#791F1F"
        view.findViewById<TextView>(R.id.tvMargin).apply {
            text = "${Math.round(margin)}% margin"; setTextColor(Color.parseColor(pillFg))
        }

        // ---- At a glance ----
        view.findViewById<TextView>(R.id.tvRemainingStock).text = qtyFormat(item.remaining)
        view.findViewById<TextView>(R.id.tvAdded).text = qtyFormat(item.added)

        // ---- Stock flow ----
        view.findViewById<TextView>(R.id.tvSold).text = "● Sold ${item.sold.toInt()}"
        view.findViewById<TextView>(R.id.tvLossQty).text = "● Loss ${item.lossQty.toInt()}"
        view.findViewById<TextView>(R.id.tvRemaining).text = "● Remaining ${item.remaining.toInt()}"

        val total = if (item.added > 0) item.added
            else (item.sold + item.lossQty + item.remaining).coerceAtLeast(1.0)
        fun seg(id: Int, v: Double) {
            val seg = view.findViewById<android.view.View>(id)
            (seg.layoutParams as LinearLayout.LayoutParams).also {
                it.weight = (v / total).toFloat().coerceAtLeast(0f); seg.layoutParams = it
            }
        }
        seg(R.id.barSold, item.sold)
        seg(R.id.barLoss, item.lossQty)
        seg(R.id.barRemaining, item.remaining)

        view.findViewById<Button>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ================= PRINT (UNCHANGED) =================
    private fun printProfitReport() {

        if (latestProfitList.isEmpty()) {
            Toast.makeText(this, getString(R.string.profitactivity_no_data_to_print), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {

            var totalRevenue = 0.0
            var totalCost = 0.0
            var totalProfit = 0.0
            var totalLoss = 0.0
            var totalExpense = 0.0

            val rows = mutableListOf<List<String>>()
            val printCurrencySymbol = CurrencyHelper.getCurrencySymbol(this@ProfitActivity)

            latestProfitList.forEach { item ->

                val netProfit = item.profit - item.lossAmount

                totalRevenue += item.revenue
                totalCost += item.cost
                totalProfit += item.profit
                totalLoss += item.lossAmount

                rows.add(
                    listOf(
                        "${item.productName} ${item.variant ?: ""}", "${item.totalQty}", "${item.unit}",
                        "$printCurrencySymbol%.2f".format(item.revenue),
                        "$printCurrencySymbol%.2f".format(item.cost),
                        "$printCurrencySymbol%.2f".format(item.profit),
                        "Added:${item.added.toInt()} | Sold:${item.sold.toInt()} | Loss:${item.lossQty.toInt()}",
                        "${item.remaining.toInt()}",
                        "$printCurrencySymbol-%.2f".format(item.lossAmount),
                        "$printCurrencySymbol%.2f".format(netProfit),
                        getInsight(item, netProfit)
                    )
                )
            }

            // 🔥 Expense from UI (already loaded)
            val expense = findViewById<TextView>(R.id.tvExpense)
                .text.toString().replace(printCurrencySymbol, "").toDoubleOrNull() ?: 0.0

            totalExpense = expense

            withContext(Dispatchers.Main) {

                if (isFinishing || isDestroyed) return@withContext

                try {
                    val (startDate, endDate) = getFilterDateRange()

                    InvoicePdfGenerator.generateProfitPdf(
                        activity = this@ProfitActivity,
                        rows = rows,
                        totalProfit = totalProfit,
                        totalRevenue = totalRevenue,
                        totalCost = totalCost,
                        totalExpense = totalExpense,
                        totalLoss = totalLoss,
                        startDate = startDate,
                        endDate = endDate
                    )

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@ProfitActivity, getString(R.string.profitactivity_print_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPrintConfirmDialog() {

        val view = layoutInflater.inflate(R.layout.dialog_confirm_print, null)

        val tvInfo = view.findViewById<TextView>(R.id.tvInfo)
        val btnPrint = view.findViewById<Button>(R.id.btnPrint)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)

        val filterText = when (currentFilter) {
            "today" -> "Today"
            "week" -> "This Week"
            "month" -> "This Month"
            "custom" -> "Custom (${customStartDate ?: ""} → ${customEndDate ?: ""})"
            else -> "All Time"
        }

        tvInfo.text = filterText

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnPrint.setOnClickListener {
            dialog.dismiss()
            printProfitReport()
        }

        dialog.show()
    }

    private fun getInsight(item: ProductProfitRaw, netProfit: Double): String {
        return when {
            netProfit < 0 -> "Loss product"
            item.lossQty > item.sold -> "High wastage"
            item.remaining > item.sold -> "Dead stock"
            else -> "Good product"
        }
    }

    private fun getFilterDateRange(): Pair<String, String> {

        // Corrected internet clock in the shop timezone (matches backend reports).
        val cal = AppTime.calendar()
        val format = AppTime.isoDate()

        val today = format.format(cal.time)

        return when (currentFilter) {

            "today" -> {
                Pair(today, today)
            }

            "week" -> {
                val startCal = AppTime.calendar()
                startCal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                val start = format.format(startCal.time)

                val endCal = AppTime.calendar()
                endCal.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
                val endRaw = format.format(endCal.time)

                val end = minOf(endRaw, today)   // 🔥 CAP HERE

                Pair(start, end)
            }

            "month" -> {
                val startCal = AppTime.calendar()
                startCal.set(Calendar.DAY_OF_MONTH, 1)
                val start = format.format(startCal.time)

                val endCal = AppTime.calendar()
                endCal.set(
                    Calendar.DAY_OF_MONTH,
                    endCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                )
                val endRaw = format.format(endCal.time)

                val end = minOf(endRaw, today)   // 🔥 CAP HERE

                Pair(start, end)
            }

            "year" -> {
                val startCal = AppTime.calendar()
                startCal.set(Calendar.DAY_OF_YEAR, 1)
                val start = format.format(startCal.time)

                val endCal = AppTime.calendar()
                endCal.set(
                    Calendar.DAY_OF_YEAR,
                    endCal.getActualMaximum(Calendar.DAY_OF_YEAR)
                )
                val endRaw = format.format(endCal.time)

                val end = minOf(endRaw, today)   // 🔥 CAP HERE

                Pair(start, end)
            }

            "custom" -> {

                val start = customStartDate ?: ""
                val endRaw = customEndDate ?: ""

                val end = if (endRaw.isNotEmpty())
                    minOf(endRaw, today)
                else
                    ""

                Pair(start, end)
            }

            else -> {
                Pair("All Time", "")
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}