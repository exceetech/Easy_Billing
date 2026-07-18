package com.example.easy_billing

import android.app.Dialog
import com.example.easy_billing.util.AppTime
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
    private lateinit var profitAdapter: ProfitAdapter
    private lateinit var etSearch: EditText
    private lateinit var btnChart: ImageButton

    private var fullList: List<ProductProfitRaw> = emptyList()

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

    // ================= ON CREATE =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profit)

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

    // ================= RECYCLER =================

    private fun setupRecycler() {
        recyclerView = findViewById(R.id.rvProducts)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Hairline dividers between product rows, matching the themed list cards.
        val divider = androidx.recyclerview.widget.DividerItemDecoration(
            this, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
        )
        androidx.core.content.ContextCompat
            .getDrawable(this, R.drawable.divider_hairline)
            ?.let { divider.setDrawable(it) }
        recyclerView.addItemDecoration(divider)

        profitAdapter = ProfitAdapter { item ->
            showProductDialog(item)
        }

        recyclerView.adapter = profitAdapter

        etSearch = findViewById(R.id.etSearch)
        btnChart = findViewById(R.id.btnChart)
    }

    // ================= SEARCH =================

    private fun setupSearch() {

        etSearch.addTextChangedListener(object : android.text.TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                val query = s.toString().lowercase()

                val filtered = fullList.filter {
                    it.productName.lowercase().contains(query) ||
                            (it.variant?.lowercase()?.contains(query) ?: false)
                }

                profitAdapter.submitList(filtered)
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

                    findViewById<TextView>(R.id.tvRevenue).text = "₹${"%.2f".format(summary.revenue)}"
                    findViewById<TextView>(R.id.tvCost).text = "₹${"%.2f".format(summary.cost)}"
                    findViewById<TextView>(R.id.tvLoss).text = "₹${"%.2f".format(summary.loss)}"
                    findViewById<TextView>(R.id.tvExpense).text = "₹${"%.2f".format(summary.expense)}"

                    // Net profit spotlight headline (ink for profit, red for loss).
                    val netTv = findViewById<TextView>(R.id.tvNetProfit)
                    netTv.text = "₹${"%.2f".format(summary.profit)}"
                    netTv.setTextColor(
                        Color.parseColor(if (summary.profit < 0) "#A32D2D" else "#1A1A18")
                    )
                    val layoutMarginPill = findViewById<android.view.View>(R.id.layoutMarginPill)
                    if (summary.growth != null) {
                        layoutMarginPill.visibility = android.view.View.VISIBLE
                        val growthPct = summary.growth.profit_percentage
                        val positive = growthPct >= 0
                        val pillFg = if (positive) "#7DDCB2" else "#F0A3A3"
                        val sign = if (positive) "+" else ""

                        findViewById<TextView>(R.id.tvMargin).apply {
                            text = "${sign}${Math.round(growthPct)}% growth"
                            setTextColor(Color.parseColor(pillFg))
                        }
                        layoutMarginPill.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(
                                Color.parseColor(if (positive) "#11402F" else "#5A1E1E")
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
                    profitAdapter.submitList(mapped)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
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
        fun money(v: Double) = "₹%,.2f".format(v)

        val name = if (item.variant.isNullOrBlank()) item.productName
            else "${item.productName} (${item.variant})"
        val netProfit = item.profit - item.lossAmount
        val netPositive = netProfit >= 0

        // ---- Header ----
        // Initials from the product NAME only (ignore the "(variant)" so we never pick
        // up a "(" — e.g. "Bisleri (1ltr)" → "BI", "Aashirvaad Atta" → "AA").
        val words = item.productName.trim().split(" ")
            .filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        val initials = when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            words.isNotEmpty() -> words[0].filter { it.isLetterOrDigit() }.take(2).uppercase()
            else -> "#"
        }
        view.findViewById<TextView>(R.id.tvAvatar).text = initials
        view.findViewById<TextView>(R.id.tvName).text = name
        view.findViewById<TextView>(R.id.tvNameSub).text =
            "${qtyFormat(item.sold)} sold · ${item.unit}"

        // ---- Net profit + margin pill ----
        view.findViewById<TextView>(R.id.tvNetProfit).apply {
            text = money(netProfit)
            setTextColor(Color.parseColor(if (netPositive) "#0F6E56" else "#A32D2D"))
        }
        val margin = if (item.revenue != 0.0) item.profit / item.revenue * 100 else 0.0
        val pillFg = if (netPositive) "#7DDCB2" else "#F0A3A3"
        view.findViewById<TextView>(R.id.tvMargin).apply {
            text = "${Math.round(margin)}% margin"; setTextColor(Color.parseColor(pillFg))
        }
        view.findViewById<android.view.View>(R.id.layoutMarginPill).backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (netPositive) "#11402F" else "#5A1E1E")
            )
        view.findViewById<ImageView>(R.id.ivMargin).apply {
            setColorFilter(Color.parseColor(pillFg))
            setImageResource(if (netPositive) R.drawable.ic_si_trend_up else R.drawable.ic_si_trend_down)
        }

        // ---- Financial grid ----
        view.findViewById<TextView>(R.id.tvRevenue).text = money(item.revenue)
        view.findViewById<TextView>(R.id.tvCost).text = money(item.cost)
        view.findViewById<TextView>(R.id.tvProfit).apply {
            text = money(item.profit)
            setTextColor(Color.parseColor(if (item.profit < 0) "#A32D2D" else "#0F6E56"))
        }
        view.findViewById<TextView>(R.id.tvLoss).text = money(item.lossAmount)

        // ---- Insight chip ----
        val insight = getInsight(item, netProfit)
        val (chipBg, chipInk, chipIcon) = when (insight) {
            "Loss product" -> Triple(R.drawable.bg_chip_high, "#A32D2D", R.drawable.ic_si_alert)
            "Dead stock", "High wastage" -> Triple(R.drawable.bg_chip_med, "#854F0B", R.drawable.ic_si_alert)
            else -> Triple(R.drawable.bg_chip_low, "#3B6D11", R.drawable.ic_check_circle)
        }
        view.findViewById<android.view.View>(R.id.chipInsight).setBackgroundResource(chipBg)
        view.findViewById<TextView>(R.id.tvInsight).apply {
            text = insight; setTextColor(Color.parseColor(chipInk))
        }
        view.findViewById<ImageView>(R.id.ivInsight).apply {
            setImageResource(chipIcon); setColorFilter(Color.parseColor(chipInk))
        }

        // ---- Stock flow ----
        view.findViewById<TextView>(R.id.tvAdded).text = "Added ${item.added.toInt()}"
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
            Toast.makeText(this, "No data to print", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {

            var totalRevenue = 0.0
            var totalCost = 0.0
            var totalProfit = 0.0
            var totalLoss = 0.0
            var totalExpense = 0.0

            val rows = mutableListOf<List<String>>()

            latestProfitList.forEach { item ->

                val netProfit = item.profit - item.lossAmount

                totalRevenue += item.revenue
                totalCost += item.cost
                totalProfit += item.profit
                totalLoss += item.lossAmount

                rows.add(
                    listOf(
                        "${item.productName} ${item.variant ?: ""}", "${item.totalQty}", "${item.unit}",
                        "₹%.2f".format(item.revenue),
                        "₹%.2f".format(item.cost),
                        "₹%.2f".format(item.profit),
                        "Added:${item.added.toInt()} | Sold:${item.sold.toInt()} | Loss:${item.lossQty.toInt()}",
                        "${item.remaining.toInt()}",
                        "₹-%.2f".format(item.lossAmount),
                        "₹%.2f".format(netProfit),
                        getInsight(item, netProfit)
                    )
                )
            }

            // 🔥 Expense from UI (already loaded)
            val expense = findViewById<TextView>(R.id.tvExpense)
                .text.toString().replace("₹", "").toDoubleOrNull() ?: 0.0

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
                    Toast.makeText(this@ProfitActivity, "Print failed", Toast.LENGTH_SHORT).show()
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

        tvInfo.text = "Print report for:\n$filterText"

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