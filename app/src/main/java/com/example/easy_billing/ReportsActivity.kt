package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.MonthlyReportAdapter
import com.example.easy_billing.adapter.PeakHourAdapter
import com.example.easy_billing.DailyReportAdapter
import com.example.easy_billing.ProductReportAdapter
import com.example.easy_billing.network.*
import com.example.easy_billing.util.BarChartMarker
import com.example.easy_billing.util.ChartMarkerView
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.RoundedBarChartRenderer
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.model.GradientColor
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

//                setupMiniChart(findViewById(R.id.chartRevenueMini), listOf(10f, 20f, 15f, 30f), getColor(R.color.primaryColor))
//
//                setupMiniChart(findViewById(R.id.chartBillsMini), listOf(5f, 8f, 6f, 10f), getColor(R.color.secondaryColor))
//
//                setupMiniChart(findViewById(R.id.chartAvgMini), listOf(2f, 3f, 4f, 3f), getColor(R.color.orange))

                val context = this@ReportsActivity

                tvRevenue.text = CurrencyHelper.format(context, avg.total_revenue)
                tvBills.text = avg.total_bills.toString()
                tvAverage.text = CurrencyHelper.format(context, avg.average_bill)

                val chartRevenueMini = findViewById<LineChart>(R.id.chartRevenueMini)
                val chartBillsMini = findViewById<LineChart>(R.id.chartBillsMini)
                val chartAvgMini = findViewById<LineChart>(R.id.chartAvgMini)

                // 🔥 Replace with real backend data if available
                val revenueList = listOf(
                    avg.prev_revenue.toFloat(),
                    avg.total_revenue.toFloat()
                )

                val billsList = listOf(
                    avg.prev_bills.toFloat(),
                    avg.total_bills.toFloat()
                )

                val avgList = listOf(
                    avg.prev_avg.toFloat(),
                    avg.average_bill.toFloat()
                )

                val revenueColor = if (isTrendPositive(revenueList))
                    getColor(R.color.green)
                else
                    getColor(R.color.red)

                val billsColor = if (isTrendPositive(billsList))
                    getColor(R.color.green)
                else
                    getColor(R.color.red)

                val avgColor = if (isTrendPositive(avgList))
                    getColor(R.color.green)
                else
                    getColor(R.color.red)

                setupMiniChart(chartRevenueMini, revenueList, revenueColor)
                setupMiniChart(chartBillsMini, billsList, billsColor)
                setupMiniChart(chartAvgMini, avgList, avgColor)

                val revenueGrowth =
                    growthPercent(avg.total_revenue, avg.prev_revenue)

                val billsGrowth =
                    growthPercent(avg.total_bills.toDouble(), avg.prev_bills.toDouble())

                val avgGrowth =
                    growthPercent(avg.average_bill, avg.prev_avg)

                tvRevenueGrowth.text = revenueGrowth.first
                tvBillsGrowth.text = billsGrowth.first
                tvAverageGrowth.text = avgGrowth.first

                styleGrowth(tvRevenueGrowth, revenueGrowth.second)
                styleGrowth(tvBillsGrowth, billsGrowth.second)
                styleGrowth(tvAverageGrowth, avgGrowth.second)

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

        val set = LineDataSet(entries, "")

        set.color = getColor(R.color.primaryColor)
        set.lineWidth = 3f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER

        set.color = getColor(R.color.primaryColor)
        set.lineWidth = 3f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER
        set.setDrawCircles(false)
        set.setDrawValues(false)

        // ✅ CROSSHAIR SETTINGS (ADD HERE)
        set.highLightColor = Color.BLACK
        set.enableDashedHighlightLine(10f, 5f, 0f)
        set.setDrawVerticalHighlightIndicator(true)
        set.setDrawHorizontalHighlightIndicator(false)

        set.setDrawFilled(true)
        set.fillDrawable = getDrawable(R.drawable.chart_gradient)

//        set.color = getColor(R.color.primaryColor)
        set.setCircleColor(getColor(R.color.primaryColor))
//        set.lineWidth = 3f
//        set.setDrawValues(false)

        chartSalesTrend.data = LineData(set)

        chartSalesTrend.highlightValue(0f, 0)

        // 🔥 SNAP + INTERACTION (ADD HERE)
        chartSalesTrend.isHighlightPerTapEnabled = true
        chartSalesTrend.isHighlightPerDragEnabled = true
        chartSalesTrend.setMaxHighlightDistance(50f)

        // Smooth drag feel
        chartSalesTrend.setDragEnabled(true)
        chartSalesTrend.setScaleEnabled(true)
        chartSalesTrend.setPinchZoom(true)
        chartSalesTrend.setDragDecelerationEnabled(true)
        chartSalesTrend.dragDecelerationFrictionCoef = 0.9f

        val marker = ChartMarkerView(this)
        chartSalesTrend.marker = marker

        stylePremiumLineChart(chartSalesTrend)

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

        val set = LineDataSet(entries, "")

        set.color = getColor(R.color.primaryColor)
        set.lineWidth = 3f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER

        set.color = getColor(R.color.primaryColor)
        set.lineWidth = 3f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER
        set.setDrawCircles(false)
        set.setDrawValues(false)

        // ✅ CROSSHAIR SETTINGS (ADD HERE)
        set.highLightColor = Color.BLACK
        set.enableDashedHighlightLine(10f, 5f, 0f)
        set.setDrawVerticalHighlightIndicator(true)
        set.setDrawHorizontalHighlightIndicator(false)

        set.setDrawFilled(true)
        set.fillDrawable = getDrawable(R.drawable.chart_gradient)

//        set.color = getColor(R.color.primaryColor)
        set.setCircleColor(getColor(R.color.primaryColor))
//        set.lineWidth = 3f
//        set.setDrawValues(false)

        chartSalesTrend.data = LineData(set)

        chartSalesTrend.highlightValue(0f, 0)

        // 🔥 SNAP + INTERACTION (ADD HERE)
        chartSalesTrend.isHighlightPerTapEnabled = true
        chartSalesTrend.isHighlightPerDragEnabled = true
        chartSalesTrend.setMaxHighlightDistance(50f)

        // Smooth drag feel
        chartSalesTrend.setDragEnabled(true)
        chartSalesTrend.setScaleEnabled(true)
        chartSalesTrend.setPinchZoom(true)
        chartSalesTrend.setDragDecelerationEnabled(true)
        chartSalesTrend.dragDecelerationFrictionCoef = 0.9f

        val marker = ChartMarkerView(this)
        chartSalesTrend.marker = marker

        stylePremiumLineChart(chartSalesTrend)

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

        val set = LineDataSet(entries, "")

        set.color = getColor(R.color.primaryColor)
        set.lineWidth = 3f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER

        set.color = getColor(R.color.primaryColor)
        set.lineWidth = 3f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER
        set.setDrawCircles(false)
        set.setDrawValues(false)


        // ✅ CROSSHAIR SETTINGS (ADD HERE)
        set.highLightColor = Color.BLACK
        set.enableDashedHighlightLine(10f, 5f, 0f)
        set.setDrawVerticalHighlightIndicator(true)
        set.setDrawHorizontalHighlightIndicator(false)

        set.setDrawFilled(true)
        set.fillDrawable = getDrawable(R.drawable.chart_gradient)

//        set.color = getColor(R.color.primaryColor)
        set.setCircleColor(getColor(R.color.primaryColor))

        chartSalesTrend.data = LineData(set)

        chartSalesTrend.highlightValue(0f, 0)

        // 🔥 SNAP + INTERACTION (ADD HERE)
        chartSalesTrend.isHighlightPerTapEnabled = true
        chartSalesTrend.isHighlightPerDragEnabled = true
        chartSalesTrend.setMaxHighlightDistance(50f)

        // Smooth drag feel
        chartSalesTrend.setDragEnabled(true)
        chartSalesTrend.setScaleEnabled(true)
        chartSalesTrend.setPinchZoom(true)
        chartSalesTrend.setDragDecelerationEnabled(true)
        chartSalesTrend.dragDecelerationFrictionCoef = 0.9f

        val marker = ChartMarkerView(this)
        chartSalesTrend.marker = marker

        stylePremiumLineChart(chartSalesTrend)

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

        val set = LineDataSet(entries, "")

        set.color = getColor(R.color.primaryColor)
        set.lineWidth = 3f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER

        set.color = getColor(R.color.primaryColor)
        set.lineWidth = 3f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER
        set.setDrawCircles(false)
        set.setDrawValues(false)

        // ✅ CROSSHAIR SETTINGS (ADD HERE)
        set.highLightColor = Color.BLACK
        set.enableDashedHighlightLine(10f, 5f, 0f)
        set.setDrawVerticalHighlightIndicator(true)
        set.setDrawHorizontalHighlightIndicator(false)

        set.setDrawFilled(true)
        set.fillDrawable = getDrawable(R.drawable.chart_gradient)

//        set.color = getColor(R.color.primaryColor)
        set.setCircleColor(getColor(R.color.primaryColor))
//        set.lineWidth = 3f
//        set.setDrawValues(false)

        chartSalesTrend.data = LineData(set)

        chartSalesTrend.highlightValue(0f, 0)

        // 🔥 SNAP + INTERACTION (ADD HERE)
        chartSalesTrend.isHighlightPerTapEnabled = true
        chartSalesTrend.isHighlightPerDragEnabled = true
        chartSalesTrend.setMaxHighlightDistance(50f)

        // Smooth drag feel
        chartSalesTrend.setDragEnabled(true)
        chartSalesTrend.setScaleEnabled(true)
        chartSalesTrend.setPinchZoom(true)
        chartSalesTrend.setDragDecelerationEnabled(true)
        chartSalesTrend.dragDecelerationFrictionCoef = 0.9f

        val marker = ChartMarkerView(this)
        chartSalesTrend.marker = marker

        stylePremiumLineChart(chartSalesTrend)

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

        val set = LineDataSet(entries, "")

        set.color = getColor(R.color.primaryColor)
        set.lineWidth = 3f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER

        set.color = getColor(R.color.primaryColor)
        set.lineWidth = 3f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER
        set.setDrawCircles(false)
        set.setDrawValues(false)

        // ✅ CROSSHAIR SETTINGS (ADD HERE)
        set.highLightColor = Color.BLACK
        set.enableDashedHighlightLine(10f, 5f, 0f)
        set.setDrawVerticalHighlightIndicator(true)
        set.setDrawHorizontalHighlightIndicator(false)

        set.setDrawFilled(true)
        set.fillDrawable = getDrawable(R.drawable.chart_gradient)

//        set.color = getColor(R.color.primaryColor)

        chartSalesTrend.data = LineData(set)

        chartSalesTrend.highlightValue(0f, 0)

        // 🔥 SNAP + INTERACTION (ADD HERE)
        chartSalesTrend.isHighlightPerTapEnabled = true
        chartSalesTrend.isHighlightPerDragEnabled = true
        chartSalesTrend.setMaxHighlightDistance(50f)

        // Smooth drag feel
        chartSalesTrend.setDragEnabled(true)
        chartSalesTrend.setScaleEnabled(true)
        chartSalesTrend.setPinchZoom(true)
        chartSalesTrend.setDragDecelerationEnabled(true)
        chartSalesTrend.dragDecelerationFrictionCoef = 0.9f

        val marker = ChartMarkerView(this)
        chartSalesTrend.marker = marker

        stylePremiumLineChart(chartSalesTrend)

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
        val secondEntries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        val sortedData = data.sortedBy { it.hour }

        sortedData.forEachIndexed { index, item ->

            // Revenue
            entries.add(BarEntry(index.toFloat(), item.revenue.toFloat()))

            // Bills count
            secondEntries.add(BarEntry(index.toFloat(), item.bills.toFloat()))

            labels.add("${item.hour}:00")
        }

        val dataSet = BarDataSet(entries, "")
        val secondDataSet = BarDataSet(secondEntries, "")

        // 🔥 Assign axis
        dataSet.axisDependency = YAxis.AxisDependency.LEFT
        secondDataSet.axisDependency = YAxis.AxisDependency.RIGHT

        // 🔥 Colors
        dataSet.color = getColor(R.color.primarySoft)
        secondDataSet.color = getColor(R.color.secondaryColor)

        dataSet.setDrawValues(false)
        secondDataSet.setDrawValues(false)

        // 🔥 Highlight
        dataSet.highLightColor = getColor(R.color.black)
        secondDataSet.highLightColor = getColor(R.color.black)

        val barData = BarData(dataSet, secondDataSet)

        // 🔥 Spacing (important)
        val barWidth = 0.35f
        val barSpace = 0.001f
        val groupSpace = 0.2f

        barData.barWidth = barWidth

        chartPeakHours.data = barData

        chartPeakHours.xAxis.axisMinimum = 0f
        chartPeakHours.xAxis.axisMaximum =
            0f + barData.getGroupWidth(groupSpace, barSpace) * entries.size

        chartPeakHours.groupBars(0f, groupSpace, barSpace)

        // 🔥 Marker
        val marker = BarChartMarker(this)
        chartPeakHours.marker = marker

        // 🔥 Style
        stylePremiumBarChart(chartPeakHours)

        // 🔥 X Axis
        chartPeakHours.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            granularity = 1f
            setDrawGridLines(false)
            textSize = 11f
            setCenterAxisLabels(true)
        }

        // 🔥 LEFT AXIS (Revenue ₹)
        chartPeakHours.axisLeft.apply {

            axisMinimum = 0f
            setDrawGridLines(true)
            gridLineWidth = 0.5f
            textSize = 11f

            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {

                    return String.format("%,d", value.toInt())
                }
            }
        }

        // 🔥 RIGHT AXIS (Bills count)
        chartPeakHours.axisRight.apply {
            axisMinimum = 0f
            setDrawGridLines(false)
            setDrawAxisLine(false)
            textSize = 11f

            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return value.toInt().toString()
                }
            }
        }

        chartPeakHours.setFitBars(true)

        // 🔥 Smooth animation
        chartPeakHours.animateY(
            900,
            com.github.mikephil.charting.animation.Easing.EaseOutCubic
        )

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

    private fun stylePremiumLineChart(chart: LineChart) {

        chart.setBackgroundColor(getColor(R.color.white))
        chart.setViewPortOffsets(40f, 20f, 20f, 40f)

        chart.axisLeft.setDrawLabels(true)
        chart.axisLeft.labelCount = 4

        chart.description.isEnabled = false
        chart.legend.isEnabled = false

        chart.setTouchEnabled(true)
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.isDoubleTapToZoomEnabled = true

        // ✨ Animation
        chart.animateX(800)

        // ❌ Remove right axis
        chart.axisRight.isEnabled = false

        // ✅ X Axis (clean)
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.setDrawAxisLine(false)
        xAxis.textColor = getColor(R.color.gray)
        xAxis.textSize = 11f

        // ✅ Y Axis (minimal)
        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = getColor(R.color.light_gray)
        leftAxis.setDrawAxisLine(false)
        leftAxis.textColor = getColor(R.color.gray)
    }

    private fun stylePremiumBarChart(chart: BarChart) {

        chart.setBackgroundColor(getColor(R.color.white))
        chart.description.isEnabled = false
        chart.legend.isEnabled = false

        // 🔥 Interaction
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)

        chart.isHighlightPerTapEnabled = true
        chart.isHighlightPerDragEnabled = true
        chart.maxHighlightDistance = 40f

        // 🔥 X Axis
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawAxisLine(false)
        xAxis.setDrawGridLines(false)
        xAxis.textColor = getColor(R.color.gray)

        // 🔥 LEFT AXIS (Revenue)
        val leftAxis = chart.axisLeft
        leftAxis.setDrawAxisLine(false)
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = getColor(R.color.light_gray)
        leftAxis.textColor = getColor(R.color.gray)

        // 🔥 RIGHT AXIS (Bills)
        val rightAxis = chart.axisRight
        rightAxis.isEnabled = true
        rightAxis.setDrawAxisLine(false)
        rightAxis.setDrawGridLines(false)
        rightAxis.textColor = getColor(R.color.gray)

        // 🔥 Clean UI
        chart.setNoDataText("")
        chart.setDrawGridBackground(false)
    }

    fun styleGrowth(view: TextView, isPositive: Boolean) {

        view.setTextColor(
            if (isPositive) getColor(R.color.green)
            else getColor(R.color.red)
        )

        view.setBackgroundResource(
            if (isPositive) R.drawable.bg_growth_positive
            else R.drawable.bg_growth_negative
        )

        view.setPadding(12, 4, 12, 4)
    }

    private fun setupMiniChart(chart: LineChart, values: List<Float>, color: Int) {

        val entries = values.mapIndexed { index, v ->
            Entry(index.toFloat(), v)
        }

        val dataSet = LineDataSet(entries, "")

        // 🔥 LINE STYLE
        dataSet.color = color
        dataSet.lineWidth = 2.5f
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER

        // ❌ remove dots & values
        dataSet.setDrawCircles(false)
        dataSet.setDrawValues(false)

        // 🔥 SOFT GLOW EFFECT
        dataSet.highLightColor = color
        dataSet.setDrawHighlightIndicators(false)

        // 🔥 GRADIENT FILL (VERY IMPORTANT)
        dataSet.setDrawFilled(true)
        dataSet.fillDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                color,
                android.graphics.Color.TRANSPARENT
            )
        )

        chart.data = LineData(dataSet)

        // 🔥 CLEAN UI
        chart.description.isEnabled = false
        chart.legend.isEnabled = false

        chart.axisLeft.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.xAxis.isEnabled = false

        // 🔥 REMOVE TOUCH (mini charts shouldn't scroll)
        chart.setTouchEnabled(false)

        // 🔥 REMOVE GRID BACKGROUND
        chart.setDrawGridBackground(false)

        // 🔥 ANIMATION (smooth entry)
        chart.animateX(600)

        chart.invalidate()
    }

    private fun isTrendPositive(values: List<Float>): Boolean {
        var score = 0
        for (i in 1 until values.size) {
            if (values[i] >= values[i - 1]) score++ else score--
        }
        return score >= 0
    }
}
