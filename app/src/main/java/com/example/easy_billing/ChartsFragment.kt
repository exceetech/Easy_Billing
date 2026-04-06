package com.example.easy_billing.fragments

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.Filterable
import com.example.easy_billing.R
import com.example.easy_billing.ReportFilter
import com.example.easy_billing.network.*
import com.example.easy_billing.util.ChartMarkerView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ChartsFragment : Fragment(R.layout.fragment_charts), Filterable {

    private lateinit var chart: LineChart

    private var currentFilter = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chart = view.findViewById(R.id.chartSalesTrend)
        loadChart()
    }

    override fun onFilterChanged(
        filter: ReportFilter,
        startDate: String?,
        endDate: String?
    ) {
        currentFilter = filter
        customStartDate = startDate
        customEndDate = endDate
        loadChart()
    }

    private fun loadChart() {

        lifecycleScope.launch {

            val token = requireActivity()
                .getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                when (currentFilter) {

                    ReportFilter.TODAY -> {
                        val hourly =
                            RetrofitClient.api.getTodayHourlySales("Bearer $token")
                        drawHourlyChart(hourly)
                    }

                    ReportFilter.WEEK -> {
                        val daily =
                            RetrofitClient.api.getDailyReport("Bearer $token")
                        drawWeeklyChart(daily)
                    }

                    ReportFilter.MONTH -> {
                        val daily =
                            RetrofitClient.api.getDailyReport("Bearer $token")
                        drawMonthChart(daily)
                    }

                    ReportFilter.YEAR -> {
                        val monthly =
                            RetrofitClient.api.getMonthlyReport("Bearer $token")
                        drawYearChart(monthly)
                    }

                    ReportFilter.CUSTOM -> {

                        if (customStartDate == null || customEndDate == null) {
                            return@launch
                        }

                        val daily =
                            RetrofitClient.api.getDailyReport("Bearer $token")

                        val filtered = filterCustomDates(daily)
                        drawCustomChart(filtered)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "Chart load failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- TODAY ----------------

    private fun drawHourlyChart(data: List<PeakHourResponse>) {

        val map = HashMap<Int, Float>()
        data.forEach { map[it.hour] = it.revenue.toFloat() }

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // ✅ ONLY ADD DATA TILL CURRENT TIME
        for (i in 0..currentHour) {

            labels.add("$i:00")
            entries.add(Entry(i.toFloat(), map[i] ?: 0f))
        }

        setupChart(entries, labels)
    }

    // ---------------- WEEK ----------------

    private fun drawWeeklyChart(data: List<DailyReportResponse>) {

        val map = HashMap<String, Float>()
        data.forEach { map[it.date] = it.revenue.toFloat() }

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        val cal = Calendar.getInstance()
        val todayIndex = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0 = Sunday

        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)

        for (i in 0..6) {

            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_WEEK, i)

            val date = sdf.format(c.time)

            labels.add(SimpleDateFormat("EEE", Locale.getDefault()).format(c.time))

            // ✅ ONLY DRAW TILL TODAY
            if (i <= todayIndex) {
                entries.add(Entry(i.toFloat(), map[date] ?: 0f))
            }
        }

        setupChart(entries, labels)
    }

    // ---------------- MONTH ----------------

    private fun drawMonthChart(data: List<DailyReportResponse>) {

        val map = HashMap<String, Float>()
        data.forEach { map[it.date] = it.revenue.toFloat() }

        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_MONTH)
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        for (i in 1..days) {

            cal.set(Calendar.DAY_OF_MONTH, i)
            val date = sdf.format(cal.time)

            labels.add(String.format("%02d", i))

            // ✅ ONLY DRAW TILL TODAY
            if (i <= today) {
                entries.add(Entry((i - 1).toFloat(), map[date] ?: 0f))
            }
        }

        setupChart(entries, labels)
    }

    // ---------------- YEAR ----------------

    private fun drawYearChart(data: List<MonthlyReportResponse>) {

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val formatter = SimpleDateFormat("MMM", Locale.getDefault())

        val map = HashMap<Int, Float>()

        // 🔥 map API data → month index (0–11)
        data.forEach {
            val date = parser.parse(it.month)!!
            val cal = Calendar.getInstance()
            cal.time = date
            val monthIndex = cal.get(Calendar.MONTH)

            map[monthIndex] = it.revenue.toFloat()
        }

        val currentMonth = Calendar.getInstance().get(Calendar.MONTH)

        // 🔥 build full year labels
        for (i in 0..11) {

            val cal = Calendar.getInstance()
            cal.set(Calendar.MONTH, i)

            labels.add(formatter.format(cal.time))

            // ✅ ONLY DRAW TILL CURRENT MONTH
            if (i <= currentMonth) {
                entries.add(Entry(i.toFloat(), map[i] ?: 0f))
            }
        }

        setupChart(entries, labels)
    }

    // ---------------- CUSTOM ----------------

    private fun drawCustomChart(data: List<DailyReportResponse>) {

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        val apiFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        val start = apiFormat.parse(customStartDate!!)!!
        val selectedEnd = apiFormat.parse(customEndDate!!)!!

        // ✅ TODAY
        val today = Date()

        // ✅ FINAL END = min(selectedEnd, today)
        val end = if (selectedEnd.after(today)) today else selectedEnd

        // ✅ Convert API data → Map
        val map = HashMap<String, Float>()
        data.forEach {
            map[it.date] = it.revenue.toFloat()
        }

        val cal = Calendar.getInstance()
        cal.time = start

        var index = 0

        // ✅ LOOP from start → (capped) end
        while (!cal.time.after(end)) {

            val dateStr = apiFormat.format(cal.time)

            labels.add(dateStr.substring(5))

            val value = map[dateStr] ?: 0f
            entries.add(Entry(index.toFloat(), value))

            cal.add(Calendar.DAY_OF_MONTH, 1)
            index++
        }

        setupChart(entries, labels)
    }

    // ---------------- COMMON ----------------

    private fun setupChart(entries: List<Entry>, labels: List<String>) {

        if (entries.isEmpty()) {
            chart.clear()
            chart.setNoDataText("No chart data available")
            return
        }

        chart.clear()

        // ================= TREND LOGIC =================
        // Compare LAST value with PREVIOUS value
        val lastIndex = entries.lastIndex

        val isUpTrend = if (entries.size >= 2) {
            entries[lastIndex].y >= entries[lastIndex - 1].y
        } else {
            true
        }

        val lineColor = if (isUpTrend)
            Color.parseColor("#22C55E") // 🟢 green
        else
            Color.parseColor("#EF4444") // 🔴 red

        val fillDrawable = if (isUpTrend)
            requireContext().getDrawable(R.drawable.chart_gradient_green)
        else
            requireContext().getDrawable(R.drawable.chart_gradient_red)

        val set = LineDataSet(entries, "")

        // ================= LINE =================
        set.color = lineColor
        set.lineWidth = 3f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER
        set.setDrawCircles(false)
        set.setDrawValues(false)

        // 🔥 ensure line always visible
        set.setDrawFilled(true)
        set.fillDrawable = fillDrawable

        // ================= HIGHLIGHT =================
        set.highLightColor = Color.argb(120, 0, 0, 0)
        set.setDrawVerticalHighlightIndicator(true)
        set.setDrawHorizontalHighlightIndicator(true)
        set.enableDashedHighlightLine(10f, 6f, 0f)

        chart.data = LineData(set)

        // ================= X AXIS =================
        chart.xAxis.apply {

            valueFormatter = IndexAxisValueFormatter(labels)
            position = XAxis.XAxisPosition.BOTTOM

            setDrawAxisLine(true)
            axisLineColor = Color.parseColor("#374151")
            axisLineWidth = 1.5f

            setDrawGridLines(true)
            gridColor = Color.argb(25, 156, 163, 175)
            enableGridDashedLine(6f, 6f, 0f)

            textColor = Color.parseColor("#6B7280")
            textSize = 10f

            granularity = 1f
            labelCount = 6
            yOffset = 8f
        }

        // ================= Y AXIS =================
        chart.axisLeft.apply {

            // ✅ PREMIUM AXIS LINE
            setDrawAxisLine(true)
            axisLineColor = Color.parseColor("#374151")
            axisLineWidth = 1.5f

            // GRID
            setDrawGridLines(true)
            gridColor = Color.argb(25, 156, 163, 175)
            enableGridDashedLine(6f, 6f, 0f)

            // TEXT
            textColor = Color.parseColor("#6B7280")
            textSize = 11f

            labelCount = 4

            xOffset = 10f
            yOffset = 6f
        }

        // ================= CLEAN =================
        chart.axisRight.isEnabled = false
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawGridBackground(false)

        // ================= INTERACTION =================
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)

        chart.isHighlightPerTapEnabled = true
        chart.isHighlightPerDragEnabled = true

        // ================= MARKER =================
        chart.marker = ChartMarkerView(requireContext())

        // ================= SPACING =================
        chart.setExtraOffsets(8f, 16f, 8f, 12f)

        // ================= ANIMATION =================
        chart.animateX(900, com.github.mikephil.charting.animation.Easing.EaseOutCubic)

        chart.invalidate()
    }

    private fun filterCustomDates(data: List<DailyReportResponse>): List<DailyReportResponse> {

        if (customStartDate == null || customEndDate == null) return data

        return data.filter {
            it.date >= customStartDate!! && it.date <= customEndDate!!
        }
    }
}