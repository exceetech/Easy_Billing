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
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.ChartMarkerView
import com.example.easy_billing.util.GlowLineChartRenderer
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

    // 🔥 H5 FIX: Locale.US guarantees ASCII digits for API dates
    private val sdf = AppTime.isoDate()   // app timezone (matches backend)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chart = view.findViewById(R.id.chartSalesTrend)
        // BlurMaskFilter (line glow) only renders on a software layer
        chart.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        syncFilterFromActivity()
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

    // 🔥 H4 FIX: ViewPager2 creates this fragment lazily — pick up the
    // filter that was selected before this tab existed.
    private fun syncFilterFromActivity() {
        (activity as? com.example.easy_billing.ReportsActivity)?.let {
            currentFilter = it.currentFilter
            customStartDate = it.customStart
            customEndDate = it.customEnd
        }
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
                            RetrofitClient.api.getTodayHourlySales(token)
                        drawHourlyChart(hourly)
                    }

                    ReportFilter.WEEK -> {
                        val daily =
                            RetrofitClient.api.getDailyReport(token)
                        drawWeeklyChart(daily)
                    }

                    ReportFilter.MONTH -> {
                        val daily =
                            RetrofitClient.api.getDailyReport(token)
                        drawMonthChart(daily)
                    }

                    ReportFilter.YEAR -> {
                        val monthly =
                            RetrofitClient.api.getMonthlyReport(token)
                        drawYearChart(monthly)
                    }

                    ReportFilter.CUSTOM -> {

                        if (customStartDate == null || customEndDate == null) {
                            return@launch
                        }

                        val daily =
                            RetrofitClient.api.getDailyReport(token)

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

        val currentHour = AppTime.calendar().get(Calendar.HOUR_OF_DAY)

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

        val cal = AppTime.calendar()
        val todayIndex = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0 = Sunday

        // 🔥 H5 FIX: locale-independent week start (Sunday)
        cal.add(
            Calendar.DAY_OF_MONTH,
            -(cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY)
        )

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

        val cal = AppTime.calendar()
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

        val parser = AppTime.isoDate()   // app timezone (matches backend)
        val formatter = SimpleDateFormat("MMM", Locale.getDefault())

        val map = HashMap<Int, Float>()

        val currentYear = AppTime.calendar().get(Calendar.YEAR)

        // 🔥 map API data → month index (0–11)
        // ✅ Only current year — API returns all years (newest first),
        // without this check older years overwrite the current one.
        data.forEach {
            val date = parser.parse(it.month)!!
            val cal = AppTime.calendar()
            cal.time = date

            if (cal.get(Calendar.YEAR) == currentYear) {
                map[cal.get(Calendar.MONTH)] = it.revenue.toFloat()
            }
        }

        val currentMonth = AppTime.calendar().get(Calendar.MONTH)

        // 🔥 build full year labels
        for (i in 0..11) {

            val cal = AppTime.calendar()
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

        val apiFormat = AppTime.isoDate()   // app timezone (matches backend)

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

        val cal = AppTime.calendar()
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
            requireContext().getDrawable(R.drawable.chart_fill_green)
        else
            requireContext().getDrawable(R.drawable.chart_fill_red)

        // ================= GLOW PASS (thick, blurred, under the line) =================
        val glowSet = LineDataSet(entries, "glow")
        glowSet.setColor(lineColor, 120)
        glowSet.lineWidth = 10f
        glowSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        glowSet.cubicIntensity = 0.18f
        glowSet.setDrawCircles(false)
        glowSet.setDrawValues(false)
        glowSet.setDrawFilled(false)
        glowSet.isHighlightEnabled = false

        // ================= MAIN LINE (crisp, smooth) + soft fill =================
        val set = LineDataSet(entries, "")
        set.color = lineColor
        set.lineWidth = 3.6f
        set.mode = LineDataSet.Mode.CUBIC_BEZIER
        set.cubicIntensity = 0.18f
        set.setDrawCircles(false)
        set.setDrawValues(false)
        set.setDrawFilled(true)
        set.fillDrawable = fillDrawable

        // ================= HIGHLIGHT (clean, vertical guide only) =================
        set.highLightColor = Color.parseColor("#C9CDD4")
        set.highlightLineWidth = 1.2f
        set.setDrawVerticalHighlightIndicator(true)
        set.setDrawHorizontalHighlightIndicator(false)
        set.enableDashedHighlightLine(8f, 6f, 0f)

        // Glow first (under), crisp line on top
        chart.renderer = GlowLineChartRenderer(chart, chart.animator, chart.viewPortHandler)
        chart.data = LineData(glowSet, set)

        // ================= X AXIS (labels only, no line/grid) =================
        chart.xAxis.apply {

            valueFormatter = IndexAxisValueFormatter(labels)
            position = XAxis.XAxisPosition.BOTTOM

            setDrawAxisLine(false)
            setDrawGridLines(false)

            textColor = Color.parseColor("#9AA0A8")
            textSize = 10f

            granularity = 1f
            labelCount = 6
            yOffset = 10f
        }

        // ================= Y AXIS (faint horizontal guides, no labels) =================
        chart.axisLeft.apply {
            setDrawAxisLine(false)
            setDrawLabels(false)

            setDrawGridLines(true)
            gridColor = Color.parseColor("#ECEFF3")
            gridLineWidth = 1f

            labelCount = 4
            // Lift the baseline below 0 so a flat/zero line still shows above the bottom edge
            axisMinimum = -(entries.maxOf { it.y }.coerceAtLeast(20f) * 0.12f)
            setSpaceTop(22f)
        }

        // ================= CLEAN =================
        chart.axisRight.isEnabled = false
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawGridBackground(false)

        // ================= INTERACTION =================
        // Drag scrubs the marker along the line (no pan/zoom)
        chart.setTouchEnabled(true)
        chart.setDragEnabled(false)
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)

        chart.isHighlightPerTapEnabled = true
        chart.isHighlightPerDragEnabled = true

        // ================= MARKER (premium, slides along the line) =================
        chart.marker = ChartMarkerView(requireContext(), labels)

        // ================= SPACING (top room so the pill never clips) =================
        chart.setExtraOffsets(8f, 30f, 8f, 12f)

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