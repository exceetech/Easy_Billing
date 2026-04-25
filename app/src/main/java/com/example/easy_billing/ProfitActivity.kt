package com.example.easy_billing

import android.app.Dialog
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
    private lateinit var btnToday: MaterialButton
    private lateinit var btnWeek: MaterialButton
    private lateinit var btnMonth: MaterialButton
    private lateinit var btnAll: MaterialButton
    private lateinit var btnCustom: MaterialButton

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
            title = ""
            setDisplayHomeAsUpEnabled(true)
        }

        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

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

        selectButton(btnAll)
        loadProfit("all")
    }

    // ================= RECYCLER =================

    private fun setupRecycler() {
        recyclerView = findViewById(R.id.rvProducts)
        recyclerView.layoutManager = LinearLayoutManager(this)

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

        btnToday.setOnClickListener {
            currentFilter = "today"
            selectButton(btnToday)
            loadProfit("today")
        }

        btnWeek.setOnClickListener {
            currentFilter = "week"
            selectButton(btnWeek)
            loadProfit("week")
        }

        btnMonth.setOnClickListener {
            currentFilter = "month"
            selectButton(btnMonth)
            loadProfit("month")
        }

        btnAll.setOnClickListener {
            currentFilter = "all"
            selectButton(btnAll)
            loadProfit("all")
        }

        btnCustom.setOnClickListener {
            currentFilter = "custom"
            openDatePicker()
        }
    }

    // ================= UI SELECTION =================

    private fun selectButton(selected: MaterialButton) {

        val buttons = listOf(btnToday, btnWeek, btnMonth, btnAll, btnCustom)

        buttons.forEach {

            val isSelected = it == selected

            it.animate().scaleX(if (isSelected) 1.05f else 1f)
                .scaleY(if (isSelected) 1.05f else 1f)
                .setDuration(200)
                .start()

            if (isSelected) {
                it.setBackgroundColor(Color.parseColor("#111827"))
                it.setTextColor(Color.WHITE)
                it.elevation = 6f
            } else {
                it.setBackgroundColor(Color.parseColor("#F3F4F6"))
                it.setTextColor(Color.parseColor("#6B7280"))
                it.elevation = 0f
            }
        }
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

            selectButton(btnCustom)

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

                val response: ProfitResponse =
                    RetrofitClient.api.getProfit(
                        "Bearer $token",
                        filter,
                        start,
                        end
                    )

                val summary = response.summary

                findViewById<TextView>(R.id.tvRevenue).text = "₹${"%.2f".format(summary.revenue)}"
                findViewById<TextView>(R.id.tvCost).text = "₹${"%.2f".format(summary.cost)}"
                findViewById<TextView>(R.id.tvNetProfit).text = "₹${"%.2f".format(summary.profit)}"
                findViewById<TextView>(R.id.tvLoss).text = "₹${"%.2f".format(summary.loss)}"
                findViewById<TextView>(R.id.tvExpense).text = "₹${"%.2f".format(summary.expense)}"

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

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    // ================= POPUP =================

    private fun showProductDialog(item: ProductProfitRaw) {

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_product_detail)

        // 🔥 FULL WIDTH DIALOG
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // ================= NAME =================
        val name = if (item.variant.isNullOrBlank())
            item.productName
        else "${item.productName} (${item.variant})"

        dialog.findViewById<TextView>(R.id.tvName).text = name

        // ================= FORMATTERS =================
        fun qtyFormat(q: Double): String {
            return if (q % 1 == 0.0) q.toInt().toString()
            else "%.2f".format(q)
        }

        fun money(v: Double): String = "₹%.2f".format(v)

        val netProfit = item.profit - item.lossAmount

        // ================= BASIC =================
        dialog.findViewById<TextView>(R.id.tvQty).text =
            "Qty: ${qtyFormat(item.totalQty)} ${item.unit}"

        dialog.findViewById<TextView>(R.id.tvRevenue).text =
            "Revenue: ${money(item.revenue)}"

        dialog.findViewById<TextView>(R.id.tvCost).text =
            "Cost: ${money(item.cost)}"

        dialog.findViewById<TextView>(R.id.tvProfit).text =
            "Profit: ${money(item.profit)}"

        // ================= EXTRA (ADD THESE IN XML) =================
        dialog.findViewById<TextView>(R.id.tvStockFlow)?.text =
            "Added: ${item.added.toInt()} | Sold: ${item.sold.toInt()} | Loss: ${item.lossQty.toInt()}"

        dialog.findViewById<TextView>(R.id.tvRemaining)?.text =
            "Remaining: ${item.remaining.toInt()}"

        dialog.findViewById<TextView>(R.id.tvLoss)?.text =
            "Loss: ${money(item.lossAmount)}"

        dialog.findViewById<TextView>(R.id.tvNetProfit)?.text =
            "Net Profit: ${money(netProfit)}"

        // ================= COLOR LOGIC =================
        dialog.findViewById<TextView>(R.id.tvProfit).setTextColor(
            if (item.profit < 0) Color.RED else Color.parseColor("#16A34A")
        )

        dialog.findViewById<TextView>(R.id.tvNetProfit)?.setTextColor(
            if (netProfit < 0) Color.RED else Color.parseColor("#16A34A")
        )

        // ================= CLOSE =================
        dialog.findViewById<Button>(R.id.btnClose).setOnClickListener {
            dialog.dismiss()
        }

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

        val cal = Calendar.getInstance()
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        val today = format.format(cal.time)

        return when (currentFilter) {

            "today" -> {
                Pair(today, today)
            }

            "week" -> {
                val startCal = Calendar.getInstance()
                startCal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                val start = format.format(startCal.time)

                val endCal = Calendar.getInstance()
                endCal.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
                val endRaw = format.format(endCal.time)

                val end = minOf(endRaw, today)   // 🔥 CAP HERE

                Pair(start, end)
            }

            "month" -> {
                val startCal = Calendar.getInstance()
                startCal.set(Calendar.DAY_OF_MONTH, 1)
                val start = format.format(startCal.time)

                val endCal = Calendar.getInstance()
                endCal.set(
                    Calendar.DAY_OF_MONTH,
                    endCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                )
                val endRaw = format.format(endCal.time)

                val end = minOf(endRaw, today)   // 🔥 CAP HERE

                Pair(start, end)
            }

            "year" -> {
                val startCal = Calendar.getInstance()
                startCal.set(Calendar.DAY_OF_YEAR, 1)
                val start = format.format(startCal.time)

                val endCal = Calendar.getInstance()
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