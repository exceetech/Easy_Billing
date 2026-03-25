package com.example.easy_billing

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.MonthlyReportAdapter
import com.example.easy_billing.adapter.PeakHourAdapter
import com.example.easy_billing.network.*
import com.example.easy_billing.util.CurrencyHelper
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ReportsActivity : BaseActivity() {

    private lateinit var tvRevenue: TextView
    private lateinit var tvBills: TextView
    private lateinit var tvAverage: TextView

    private lateinit var rvProducts: RecyclerView
    private lateinit var rvPeakHours: RecyclerView
    private lateinit var rvDaily: RecyclerView
    private lateinit var rvMonthly: RecyclerView

    private lateinit var chartSalesTrend: LineChart
    private lateinit var chartPeakHours: BarChart

    private lateinit var tvRevenueGrowth: TextView
    private lateinit var tvBillsGrowth: TextView
    private lateinit var tvAverageGrowth: TextView

    private var salesFilter = ReportFilter.TODAY
    private var peakFilter = ReportFilter.TODAY

    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())


    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "


        tvRevenue = findViewById(R.id.tvRevenue)
        tvBills = findViewById(R.id.tvBills)
        tvAverage = findViewById(R.id.tvAverage)

        rvProducts = findViewById(R.id.rvProducts)
        rvPeakHours = findViewById(R.id.rvPeakHours)
        rvDaily = findViewById(R.id.rvDaily)
        rvMonthly = findViewById(R.id.rvMonthly)

        tvRevenueGrowth = findViewById(R.id.tvRevenueGrowth)
        tvBillsGrowth = findViewById(R.id.tvBillsGrowth)
        tvAverageGrowth = findViewById(R.id.tvAverageGrowth)

        chartSalesTrend = findViewById(R.id.chartSalesTrend)
        chartPeakHours = findViewById(R.id.chartPeakHours)

        rvProducts.layoutManager = LinearLayoutManager(this)
        rvPeakHours.layoutManager = LinearLayoutManager(this)
        rvDaily.layoutManager = LinearLayoutManager(this)
        rvMonthly.layoutManager = LinearLayoutManager(this)

        setupFilters()

        findViewById<MaterialButton>(R.id.btnSendReport)
            .setOnClickListener { showReportOptions() }

        loadAllReports()
    }

    // ---------------- FILTERS ----------------

    private fun setupFilters() {

        findViewById<Chip>(R.id.chipToday).setOnClickListener {
            salesFilter = ReportFilter.TODAY
            peakFilter = ReportFilter.TODAY
            loadAllReports()
        }

        findViewById<Chip>(R.id.chipWeek).setOnClickListener {
            salesFilter = ReportFilter.WEEK
            peakFilter = ReportFilter.WEEK
            loadAllReports()
        }

        findViewById<Chip>(R.id.chipMonth).setOnClickListener {
            salesFilter = ReportFilter.MONTH
            peakFilter = ReportFilter.MONTH
            loadAllReports()
        }

        findViewById<Chip>(R.id.chipYear).setOnClickListener {
            salesFilter = ReportFilter.YEAR
            peakFilter = ReportFilter.YEAR
            loadAllReports()
        }

        findViewById<Chip>(R.id.chipCustom).setOnClickListener {
            openCustomDatePicker()
        }
    }

    // ---------------- DATE PICKER ----------------

    private fun openCustomDatePicker() {

        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Date Range")
            .build()

        picker.show(supportFragmentManager, "DATE_RANGE")

        picker.addOnPositiveButtonClickListener {

            customStartDate = sdf.format(Date(it.first))
            customEndDate = sdf.format(Date(it.second))

            salesFilter = ReportFilter.CUSTOM
            peakFilter = ReportFilter.CUSTOM

            loadAllReports()
        }
    }

    // ---------------- LOAD REPORTS ----------------

    private fun loadAllReports() {

        loadKPI()
        loadSalesTrend()
        loadPeakHours()
        loadDailySales()
        loadMonthlySales()
        loadProducts()
    }


    private fun loadKPI() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val calendar = Calendar.getInstance()

                var start: String? = null
                var end: String? = null
                var type = ""

                when (salesFilter) {

                    ReportFilter.TODAY -> {

                        type = "today"
                    }

                    ReportFilter.WEEK -> {

                        calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)

                        start = sdf.format(calendar.time)

                        calendar.add(Calendar.DAY_OF_WEEK, 6)

                        end = sdf.format(calendar.time)

                        type = "custom"
                    }

                    ReportFilter.MONTH -> {

                        calendar.set(Calendar.DAY_OF_MONTH, 1)

                        start = sdf.format(calendar.time)

                        calendar.set(
                            Calendar.DAY_OF_MONTH,
                            calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                        )

                        end = sdf.format(calendar.time)

                        type = "custom"
                    }

                    ReportFilter.YEAR -> {

                        calendar.set(Calendar.MONTH, Calendar.JANUARY)
                        calendar.set(Calendar.DAY_OF_MONTH, 1)

                        start = sdf.format(calendar.time)

                        calendar.set(Calendar.MONTH, Calendar.DECEMBER)
                        calendar.set(Calendar.DAY_OF_MONTH, 31)

                        end = sdf.format(calendar.time)

                        type = "custom"
                    }

                    ReportFilter.CUSTOM -> {

                        start = customStartDate
                        end = customEndDate
                        type = "custom"
                    }
                }

                val avg = RetrofitClient.api.getAverageBill(
                    "Bearer $token",
                    type,
                    start,
                    end
                )

                val context = this@ReportsActivity

                tvRevenue.text = CurrencyHelper.format(context, avg.total_revenue)
                tvBills.text = avg.total_bills.toString()
                tvAverage.text = CurrencyHelper.format(context, avg.average_bill)

                val revenueGrowth =
                    growthPercent(avg.total_revenue, avg.prev_revenue)

                val billsGrowth =
                    growthPercent(avg.total_bills.toDouble(), avg.prev_bills.toDouble())

                val avgGrowth =
                    growthPercent(avg.average_bill, avg.prev_avg)

                tvRevenueGrowth.text = revenueGrowth.first
                tvBillsGrowth.text = billsGrowth.first
                tvAverageGrowth.text = avgGrowth.first

                tvRevenueGrowth.setTextColor(
                    if (revenueGrowth.second) getColor(R.color.green)
                    else getColor(R.color.red)
                )

                tvBillsGrowth.setTextColor(
                    if (billsGrowth.second) getColor(R.color.green)
                    else getColor(R.color.red)
                )

                tvAverageGrowth.setTextColor(
                    if (avgGrowth.second) getColor(R.color.green)
                    else getColor(R.color.red)
                )

            } catch (e: Exception) {

                e.printStackTrace()

                Toast.makeText(
                    this@ReportsActivity,
                    "Failed to load overview",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------------- SALES TREND ----------------

    private fun loadSalesTrend() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                when (salesFilter) {

                    ReportFilter.TODAY -> {

                        val hourly =
                            RetrofitClient.api.getTodayHourlySales("Bearer $token")

                        drawHourlySalesChart(hourly)
                    }

                    ReportFilter.WEEK -> {

                        val daily =
                            RetrofitClient.api.getDailyReport("Bearer $token")

                        drawWeeklyChart(daily)
                    }

                    ReportFilter.MONTH -> {

                        val daily =
                            RetrofitClient.api.getDailyReport("Bearer $token")

                        drawCurrentMonthChart(daily)
                    }

                    ReportFilter.YEAR -> {

                        val monthly =
                            RetrofitClient.api.getMonthlyReport("Bearer $token")

                        drawMonthlySalesChart(monthly)
                    }

                    ReportFilter.CUSTOM -> {

                        val daily =
                            RetrofitClient.api.getDailyReport("Bearer $token")

                        val filtered = filterCustomDates(daily)

                        drawCustomChart(filtered)
                    }
                }

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }
    }

    // ---------------- HOURLY CHART ----------------

    private fun drawHourlySalesChart(data: List<PeakHourResponse>) {

        chartSalesTrend.clear()

        val hourMap = HashMap<Int, Float>()

        data.forEach {
            hourMap[it.hour] = it.revenue.toFloat()
        }

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        for (i in 0..23) {

            val revenue = hourMap[i] ?: 0f

            entries.add(Entry(i.toFloat(), revenue))
            labels.add("$i:00")
        }

        val set = LineDataSet(entries, "Hourly Sales")

        set.color = getColor(R.color.primaryColor)
        set.setCircleColor(getColor(R.color.primaryColor))
        set.lineWidth = 3f
        set.setDrawValues(false)

        chartSalesTrend.data = LineData(set)

        val xAxis = chartSalesTrend.xAxis
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        xAxis.position = XAxis.XAxisPosition.BOTTOM

        chartSalesTrend.invalidate()
    }

    // ---------------- WEEK CHART ----------------

    private fun drawWeeklyChart(data: List<DailyReportResponse>) {

        chartSalesTrend.clear()

        val revenueMap = HashMap<String, Float>()

        data.forEach {
            revenueMap[it.date] = it.revenue.toFloat()
        }

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        val calendar = Calendar.getInstance()

        // Move calendar to Sunday
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)

        val weekStart = calendar.clone() as Calendar

        for (i in 0..6) {

            val dayCalendar = weekStart.clone() as Calendar
            dayCalendar.add(Calendar.DAY_OF_WEEK, i)

            val dateStr = sdf.format(dayCalendar.time)

            val revenue = revenueMap[dateStr] ?: 0f

            entries.add(
                Entry(i.toFloat(), revenue)
            )

            labels.add(
                SimpleDateFormat("EEE", Locale.getDefault()).format(dayCalendar.time)
            )
        }

        val set = LineDataSet(entries, "Weekly Sales")

        set.color = getColor(R.color.primaryColor)
        set.setCircleColor(getColor(R.color.primaryColor))
        set.lineWidth = 3f
        set.setDrawValues(false)

        chartSalesTrend.data = LineData(set)

        val xAxis = chartSalesTrend.xAxis
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.granularity = 1f

        chartSalesTrend.invalidate()
    }

    // ---------------- MONTH CHART ----------------

    private fun drawCurrentMonthChart(data: List<DailyReportResponse>) {

        chartSalesTrend.clear()

        val revenueMap = HashMap<String, Float>()

        data.forEach {
            revenueMap[it.date] = it.revenue.toFloat()
        }

        val calendar = Calendar.getInstance()

        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)

        calendar.set(year, month, 1)

        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        for (i in 0 until daysInMonth) {

            calendar.set(year, month, i + 1)

            val dateStr = sdf.format(calendar.time)

            val revenue = revenueMap[dateStr] ?: 0f

            entries.add(Entry(i.toFloat(), revenue))
            labels.add(SimpleDateFormat("dd").format(calendar.time))
        }

        val set = LineDataSet(entries, "Monthly Sales")

        set.color = getColor(R.color.primaryColor)
        set.setCircleColor(getColor(R.color.primaryColor))

        chartSalesTrend.data = LineData(set)

        chartSalesTrend.xAxis.valueFormatter =
            IndexAxisValueFormatter(labels)

        chartSalesTrend.invalidate()
    }

    // ---------------- YEAR CHART ----------------

    private fun drawMonthlySalesChart(data: List<MonthlyReportResponse>) {

        chartSalesTrend.clear()

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val formatter = SimpleDateFormat("MMM", Locale.getDefault())

        data.forEachIndexed { index, item ->

            entries.add(
                Entry(index.toFloat(), item.revenue.toFloat())
            )

            val date = parser.parse(item.month)
            labels.add(formatter.format(date!!))
        }

        val set = LineDataSet(entries, "Yearly Sales")

        set.color = getColor(R.color.primaryColor)
        set.setCircleColor(getColor(R.color.primaryColor))
        set.lineWidth = 3f
        set.setDrawValues(false)

        chartSalesTrend.data = LineData(set)

        val xAxis = chartSalesTrend.xAxis
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.granularity = 1f

        chartSalesTrend.invalidate()
    }

    // ---------------- CUSTOM CHART ----------------

    private fun drawCustomChart(data: List<DailyReportResponse>) {

        chartSalesTrend.clear()

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        data.sortedBy { it.date }
            .forEachIndexed { index, item ->

                entries.add(
                    Entry(index.toFloat(), item.revenue.toFloat())
                )

                labels.add(item.date.substring(5))
            }

        val set = LineDataSet(entries, "Custom Sales")

        set.color = getColor(R.color.primaryColor)

        chartSalesTrend.data = LineData(set)

        chartSalesTrend.xAxis.valueFormatter =
            IndexAxisValueFormatter(labels)

        chartSalesTrend.invalidate()
    }

    // ---------------- PEAK HOURS ----------------

    private fun loadPeakHours() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            val type = when (peakFilter) {

                ReportFilter.TODAY -> "today"
                ReportFilter.WEEK -> "week"
                ReportFilter.MONTH -> "month"
                ReportFilter.YEAR -> "year"
                ReportFilter.CUSTOM -> "custom"
            }

            val peak =
                RetrofitClient.api.getPeakHours(
                    "Bearer $token",
                    type,
                    customStartDate,
                    customEndDate
                )

            drawPeakHoursChart(peak)

            rvPeakHours.adapter =
                PeakHourAdapter(peak)
        }
    }

    private fun drawPeakHoursChart(data: List<PeakHourResponse>) {

        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        data.sortedBy { it.hour }
            .forEachIndexed { index, item ->

                entries.add(
                    BarEntry(index.toFloat(), item.revenue.toFloat())
                )

                labels.add("${item.hour}:00")
            }

        val dataSet = BarDataSet(entries, "Peak Revenue")

        dataSet.color = getColor(R.color.primaryColor)

        chartPeakHours.data = BarData(dataSet)

        chartPeakHours.xAxis.valueFormatter =
            IndexAxisValueFormatter(labels)

        chartPeakHours.invalidate()
    }

    // ---------------- TABLES ----------------

    private fun loadDailySales() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            val daily =
                RetrofitClient.api.getDailyReport("Bearer $token")

            rvDaily.adapter = DailyReportAdapter(daily)
        }
    }

    private fun loadMonthlySales() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            val monthly =
                RetrofitClient.api.getMonthlyReport("Bearer $token")

            rvMonthly.adapter = MonthlyReportAdapter(monthly)
        }
    }

    private fun loadProducts() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            val products =
                RetrofitClient.api.getTopProducts("Bearer $token")

            rvProducts.adapter =
                ProductReportAdapter(products)
        }
    }

    // ---------------- UTIL ----------------

    private fun filterCustomDates(
        data: List<DailyReportResponse>
    ): List<DailyReportResponse> {

        if (customStartDate == null || customEndDate == null)
            return data

        return data.filter {

            it.date >= customStartDate!! &&
                    it.date <= customEndDate!!
        }
    }

    private fun showReportOptions() {

        val options = arrayOf(
            "Today's Bills",
            "Weekly Bills",
            "Monthly Bills",
            "Custom Report"
        )

        AlertDialog.Builder(this)
            .setTitle("Send Report")
            .setItems(options) { _, which ->

                when (which) {

                    0 -> sendEmailReport("today")

                    1 -> sendEmailReport("weekly")

                    2 -> sendEmailReport("monthly")

                    3 -> openEmailDatePicker()

                }

            }
            .show()
    }

    private fun openEmailDatePicker() {

        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Report Range")
            .build()

        picker.show(supportFragmentManager, "EMAIL_RANGE")

        picker.addOnPositiveButtonClickListener {

            val start = sdf.format(Date(it.first))
            val end = sdf.format(Date(it.second))

            sendEmailReport(
                type = "custom",
                startDate = start,
                endDate = end
            )
        }
    }

    private fun sendEmailReport(
        type: String,
        startDate: String? = null,
        endDate: String? = null
    ) {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            if (token == null) {
                Toast.makeText(
                    this@ReportsActivity,
                    "Authentication error. Please login again.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            try {

                val response = RetrofitClient.api.sendEmailReport(
                    token = "Bearer $token",
                    type = type,
                    startDate = startDate,
                    endDate = endDate
                )

                Toast.makeText(
                    this@ReportsActivity,
                    response.message ?: "Report sent successfully 📧",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {

                Toast.makeText(
                    this@ReportsActivity,
                    "Failed to send report",
                    Toast.LENGTH_SHORT
                ).show()

            }
        }
    }

    private fun growthPercent(current: Double, previous: Double): Pair<String, Boolean> {

        if (previous == 0.0) return Pair("0%", true)

        val change = ((current - previous) / previous) * 100

        val formatted = String.format("%.1f%%", kotlin.math.abs(change))

        return if (change >= 0)
            Pair("↑ $formatted", true)
        else
            Pair("↓ $formatted", false)
    }
}