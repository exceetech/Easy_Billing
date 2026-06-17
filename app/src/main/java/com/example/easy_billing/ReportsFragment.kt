package com.example.easy_billing.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.Filterable
import com.example.easy_billing.R
import com.example.easy_billing.ReportFilter
import com.example.easy_billing.adapter.SalesRow
import com.example.easy_billing.adapter.SalesRowAdapter
import com.example.easy_billing.network.DailyReportResponse
import com.example.easy_billing.network.MonthlyReportResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.BarValueMarker
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.RoundedBarChartRenderer
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Sales screen — redesigned (bar-chart layout):
 * header + Daily/Monthly toggle + total revenue with growth delta +
 * rounded bar chart (best period highlighted) + 2 metric cards + breakdown list.
 */
class ReportsFragment : Fragment(R.layout.fragment_reports), Filterable {

    private enum class SalesView { DAILY, MONTHLY }

    private val blue = Color.parseColor("#378ADD")
    private val teal = Color.parseColor("#1D9E75")

    // Views
    private lateinit var tvPeriodChip: TextView
    private lateinit var segContainer: LinearLayout
    private lateinit var segDaily: TextView
    private lateinit var segMonthly: TextView
    private lateinit var tvTotalValue: TextView
    private lateinit var tvDelta: TextView
    private lateinit var tvLegendMain: TextView
    private lateinit var tvLegendBest: TextView
    private lateinit var barChart: BarChart
    private lateinit var tvBestLabel: TextView; private lateinit var tvBestValue: TextView; private lateinit var tvBestSub: TextView
    private lateinit var tvAvgLabel: TextView;  private lateinit var tvAvgValue: TextView;  private lateinit var tvAvgSub: TextView
    private lateinit var tvBreakdownLabel: TextView
    private lateinit var rvSales: RecyclerView
    private lateinit var adapter: SalesRowAdapter

    // State / data
    private var currentView = SalesView.DAILY
    private var weekOffset = 0   // 0 = this week, 1 = last week, ...
    private var yearOffset = 0   // 0 = this year, 1 = last year, ...
    private var dailyData: List<DailyReportResponse> = emptyList()
    private var monthlyData: List<MonthlyReportResponse> = emptyList()

    private val iso       = AppTime.isoDate()
    private val eeeFmt     = AppTime.formatter("EEE")
    private val ddMmmFmt   = AppTime.formatter("dd MMM")

    // Breakdown-list formatters (independent of the selected period)
    private val dayListFmt   = AppTime.formatter("EEE, dd MMM yyyy")
    private val monthListFmt = AppTime.formatter("MMM yyyy")
    private val monthParse   = AppTime.formatter("yyyy-MM-dd").apply { isLenient = true }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvPeriodChip = view.findViewById(R.id.tvPeriodChip)
        segContainer = view.findViewById(R.id.segContainer)
        segDaily     = view.findViewById(R.id.segDaily)
        segMonthly   = view.findViewById(R.id.segMonthly)
        tvTotalValue = view.findViewById(R.id.tvTotalValue)
        tvDelta      = view.findViewById(R.id.tvDelta)
        tvLegendMain = view.findViewById(R.id.tvLegendMain)
        tvLegendBest = view.findViewById(R.id.tvLegendBest)
        barChart     = view.findViewById(R.id.barChart)
        tvBestLabel  = view.findViewById(R.id.tvBestLabel); tvBestValue = view.findViewById(R.id.tvBestValue); tvBestSub = view.findViewById(R.id.tvBestSub)
        tvAvgLabel   = view.findViewById(R.id.tvAvgLabel);  tvAvgValue  = view.findViewById(R.id.tvAvgValue);  tvAvgSub  = view.findViewById(R.id.tvAvgSub)
        tvBreakdownLabel = view.findViewById(R.id.tvBreakdownLabel)
        rvSales = view.findViewById(R.id.rvSales)

        rvSales.layoutManager = LinearLayoutManager(requireContext())
        rvSales.isNestedScrollingEnabled = false
        adapter = SalesRowAdapter()
        rvSales.adapter = adapter

        segContainer.background = GradientDrawable().apply {
            cornerRadius = dp(12f); setColor(Color.parseColor("#F1EEE9"))
        }
        segDaily.setOnClickListener { switchTo(SalesView.DAILY) }
        segMonthly.setOnClickListener { switchTo(SalesView.MONTHLY) }
        applySegmentStyle()

        tvPeriodChip.setOnClickListener { showPeriodMenu(it) }

        setupChartChrome()
        loadReports()
    }

    override fun onFilterChanged(filter: ReportFilter, startDate: String?, endDate: String?) = loadReports()

    private fun loadReports() {
        lifecycleScope.launch {
            val token = requireActivity()
                .getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch
            try {
                dailyData   = RetrofitClient.api.getDailyReport(token)
                monthlyData = RetrofitClient.api.getMonthlyReport(token)
                render()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "Failed to load sales", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchTo(v: SalesView) {
        if (currentView == v) return
        currentView = v
        applySegmentStyle()
        render()
    }

    private fun applySegmentStyle() {
        val daily = currentView == SalesView.DAILY
        segDaily.background   = if (daily) pill() else null
        segMonthly.background = if (!daily) pill() else null
        segDaily.setTextColor(Color.parseColor(if (daily) "#14161A" else "#6B7280"))
        segMonthly.setTextColor(Color.parseColor(if (!daily) "#14161A" else "#6B7280"))
    }

    private fun pill() = GradientDrawable().apply { cornerRadius = dp(9f); setColor(Color.WHITE) }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun render() {
        if (currentView == SalesView.DAILY) renderDaily() else renderMonthly()
        updateBreakdownList()
    }

    /**
     * Breakdown list is independent of the selected period: the recent
     * 720 days (Daily) or 60 months (Monthly), most-recent-first.
     */
    private fun updateBreakdownList() {
        if (currentView == SalesView.DAILY) {
            val today = AppTime.todayIso()
            val src = dailyData.sortedByDescending { it.date }.take(720)
            val max = src.maxOfOrNull { it.revenue } ?: 0.0
            val rows = src.map { d ->
                val label = if (d.date == today) "Today"
                            else runCatching { dayListFmt.format(iso.parse(d.date)!!) }.getOrDefault(d.date)
                SalesRow(label, d.revenue, d.bills, isBest = max > 0 && d.revenue == max)
            }
            adapter.updateData(rows, max)
        } else {
            val src = monthlyData.sortedByDescending { it.month }.take(60)
            val max = src.maxOfOrNull { it.revenue } ?: 0.0
            val rows = src.map { mn ->
                val label = runCatching { monthListFmt.format(monthParse.parse(mn.month)!!) }.getOrDefault(mn.month)
                SalesRow(label, mn.revenue, mn.bills, isBest = max > 0 && mn.revenue == max)
            }
            adapter.updateData(rows, max)
        }
    }

    private data class Pt(val iso: String, val short: String, val full: String, val rev: Double, val bills: Int)

    /** Sunday (week start) of the week that is [offset] weeks before this one. */
    private fun weekStart(offset: Int): Calendar {
        val c = AppTime.calendar()
        c.add(Calendar.DAY_OF_YEAR, -((c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY + 7) % 7))
        c.add(Calendar.DAY_OF_YEAR, -7 * offset)
        return c
    }

    /** The 7 days (Sun→Sat) of the selected week. */
    private fun weekPoints(offset: Int): List<Pt> {
        val map = dailyData.associateBy { it.date }
        val today = AppTime.todayIso()
        val start = weekStart(offset)
        return (0..6).map { i ->
            val c = start.clone() as Calendar; c.add(Calendar.DAY_OF_YEAR, i)
            val ds = iso.format(c.time)
            val r = map[ds]
            val full = if (offset == 0 && ds == today) "Today, " + ddMmmFmt.format(c.time)
                       else eeeFmt.format(c.time) + ", " + ddMmmFmt.format(c.time)
            Pt(ds, eeeFmt.format(c.time), full, r?.revenue ?: 0.0, r?.bills ?: 0)
        }
    }

    /** The 12 months (Jan→Dec) of the selected year. */
    private fun yearPoints(offset: Int): List<Pt> {
        val year = AppTime.calendar().get(Calendar.YEAR) - offset
        val ymFmt   = AppTime.formatter("yyyy-MM")
        val monShort = AppTime.formatter("MMM")
        val monFull  = AppTime.formatter("MMM yyyy")
        return (0..11).map { m ->
            val c = AppTime.calendar()
            c.set(Calendar.YEAR, year); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, 1)
            val ym = ymFmt.format(c.time)
            val row = monthlyData.firstOrNull { it.month.startsWith(ym) }
            Pt(ym, monShort.format(c.time), monFull.format(c.time), row?.revenue ?: 0.0, row?.bills ?: 0)
        }
    }

    private fun weekLabel(offset: Int) = when (offset) {
        0 -> "This week"; 1 -> "Last week"; else -> "$offset weeks ago"
    }

    private fun yearLabel(offset: Int): String {
        val year = AppTime.calendar().get(Calendar.YEAR) - offset
        return when (offset) { 0 -> "This year"; 1 -> "Last year"; else -> year.toString() }
    }

    /** Themed dropdown to pick the week (Daily) or the year (Monthly). */
    private fun showPeriodMenu(anchor: View) {
        if (currentView == SalesView.DAILY) {
            com.example.easy_billing.ui.ThemedDropdown.show(
                anchor, (0..4).map { weekLabel(it) }, weekOffset
            ) { idx -> weekOffset = idx; render() }
        } else {
            com.example.easy_billing.ui.ThemedDropdown.show(
                anchor, (0..3).map { yearLabel(it) }, yearOffset
            ) { idx -> yearOffset = idx; render() }
        }
    }

    private fun renderDaily() {
        tvLegendMain.text = "Daily revenue"; tvLegendBest.text = "Best day"
        tvBestLabel.text = "Best day"; tvAvgLabel.text = "Avg / day"
        tvBreakdownLabel.text = "Daily breakdown"
        tvPeriodChip.text = weekLabel(weekOffset) + "  ▾"

        val pts = weekPoints(weekOffset)
        val prev = weekPoints(weekOffset + 1).sumOf { it.rev }
        // Headline = selected week's total revenue; delta vs the previous week.
        bindPeriod(pts, headline = pts.sumOf { it.rev }, prevForDelta = prev, deltaText = "vs previous week")
    }

    private fun renderMonthly() {
        tvLegendMain.text = "Monthly revenue"; tvLegendBest.text = "Best month"
        tvBestLabel.text = "Best month"; tvAvgLabel.text = "Avg / month"
        tvBreakdownLabel.text = "Monthly breakdown"
        tvPeriodChip.text = yearLabel(yearOffset) + "  ▾"

        val pts = yearPoints(yearOffset)
        val prev = yearPoints(yearOffset + 1).sumOf { it.rev }
        // Headline = selected year's total revenue; delta vs the previous year.
        bindPeriod(pts, headline = pts.sumOf { it.rev }, prevForDelta = prev, deltaText = "vs previous year")
    }

    /**
     * Shared binding for whichever period list is active.
     * @param headline the big "Total revenue" number (week total / this month).
     * @param prevForDelta the comparison value for the growth chip.
     */
    private fun bindPeriod(pts: List<Pt>, headline: Double, prevForDelta: Double, deltaText: String) {
        val ctx = requireContext()
        if (pts.isEmpty()) {
            tvTotalValue.text = CurrencyHelper.format(ctx, 0.0)
            tvDelta.visibility = View.GONE
            barChart.clear(); barChart.invalidate()
            tvBestValue.text = "—"; tvBestSub.text = "—"; tvAvgValue.text = "—"; tvAvgSub.text = "—"
            adapter.updateData(emptyList(), 0.0)
            return
        }

        val sumRev     = pts.sumOf { it.rev }
        val totalBills = pts.sumOf { it.bills }
        val bestIdx    = pts.indices.maxByOrNull { pts[it].rev } ?: 0
        val best       = pts[bestIdx]
        val avg        = sumRev / pts.size

        // Headline + growth delta
        tvTotalValue.text = CurrencyHelper.format(ctx, headline)
        if (prevForDelta > 0) {
            val pct = ((headline - prevForDelta) / prevForDelta * 100).roundToInt()
            val up  = pct >= 0
            tvDelta.visibility = View.VISIBLE
            tvDelta.text = (if (up) "↑ " else "↓ ") + "${abs(pct)}% $deltaText"
            tvDelta.setTextColor(Color.parseColor(if (up) "#1A7F37" else "#B42318"))
            tvDelta.background = GradientDrawable().apply {
                cornerRadius = dp(8f); setColor(Color.parseColor(if (up) "#E6F4EA" else "#FEECEC"))
            }
        } else {
            tvDelta.visibility = View.GONE
        }

        // Metric cards
        tvBestValue.text = CurrencyHelper.format(ctx, best.rev)
        tvBestSub.text   = best.full
        tvAvgValue.text  = CurrencyHelper.format(ctx, avg)
        tvAvgSub.text    = "$totalBills bills"

        // Bar chart
        drawBars(pts, bestIdx)
        // (breakdown list is populated separately by updateBreakdownList)
    }

    private fun drawBars(pts: List<Pt>, bestIdx: Int) {
        // data = Pair(label, bills) so the touch marker can show them
        val entries = pts.mapIndexed { i, p -> BarEntry(i.toFloat(), p.rev.toFloat(), Pair(p.full, p.bills)) }
        val ds = BarDataSet(entries, "").apply {
            colors = pts.indices.map { if (it == bestIdx) teal else blue }
            setDrawValues(false)
            highLightAlpha = 0   // no rectangular overlay on tap — just the marker
        }
        val data = BarData(ds).apply { barWidth = 0.6f }
        barChart.data = data

        val maxRev = (pts.maxOf { it.rev }).toFloat().takeIf { it > 0f } ?: 1f
        barChart.axisLeft.axisMaximum  = maxRev * 1.30f
        barChart.axisRight.axisMaximum = maxRev * 1.30f

        barChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String =
                pts.getOrNull(value.roundToInt())?.short ?: ""
        }
        barChart.setFitBars(true)
        barChart.renderer = RoundedBarChartRenderer(barChart, barChart.animator, barChart.viewPortHandler, 4f)
        barChart.animateY(700)
        barChart.invalidate()
    }

    /** One-time static chart styling. */
    private fun setupChartChrome() {
        barChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setScaleEnabled(false)
            setPinchZoom(false)
            isDoubleTapToZoomEnabled = false
            setDrawGridBackground(false)
            setTouchEnabled(true)
            isHighlightPerTapEnabled = true
            isHighlightPerDragEnabled = false
            marker = BarValueMarker(requireContext())
            setExtraOffsets(4f, 18f, 4f, 4f)
            axisRight.isEnabled = false
            axisLeft.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisRight.axisMinimum = 0f
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                setDrawAxisLine(false)
                granularity = 1f
                textColor = Color.parseColor("#9AA0A8")
                textSize = 11f
                yOffset = 6f
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
