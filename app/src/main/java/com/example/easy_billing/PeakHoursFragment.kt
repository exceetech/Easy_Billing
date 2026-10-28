package com.example.easy_billing.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
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
import com.example.easy_billing.adapter.RankedRowAdapter
import com.example.easy_billing.network.PeakHourResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.BarBubbleMarker
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.DayPartBarChart
import com.example.easy_billing.util.RoundedBarChartRenderer
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class PeakHoursFragment : Fragment(R.layout.fragment_peak_hours), Filterable {

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var rvPeakHours: RecyclerView
    private lateinit var barChart: BarChart

    private lateinit var tvPeakRevenue: TextView
    private lateinit var tvDelta: TextView

    private lateinit var tvBreakdownMore: View
    private lateinit var tvBreakdownMoreText: TextView
    private lateinit var ivBreakdownMoreChevron: View

    private lateinit var tvBestValue: TextView
    private lateinit var tvBestSub: TextView
    private lateinit var tvAvgValue: TextView
    private lateinit var tvAvgSub: TextView

    // ── State ──────────────────────────────────────────────────────────────
    private lateinit var adapter: RankedRowAdapter
    private var currentFilter    = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String?   = null
    private var currentData: List<PeakHourResponse> = emptyList()

    private val blue = Color.parseColor("#B8895A")
    private val teal = Color.parseColor("#0F6E56")

    private val sdf = AppTime.isoDate()   // app timezone (matches backend)

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.example.easy_billing.util.UserEventLogger.logAction("PeakHoursFragment", "opened")

        tvPeakRevenue = view.findViewById(R.id.tvPeakRevenue)
        tvDelta       = view.findViewById(R.id.tvDelta)
        tvBreakdownMore = view.findViewById(R.id.tvBreakdownMore)
        tvBreakdownMoreText = view.findViewById(R.id.tvBreakdownMoreText)
        ivBreakdownMoreChevron = view.findViewById(R.id.ivBreakdownMoreChevron)

        tvBestValue   = view.findViewById(R.id.tvBestValue)
        tvBestSub     = view.findViewById(R.id.tvBestSub)
        tvAvgValue    = view.findViewById(R.id.tvAvgValue)
        tvAvgSub      = view.findViewById(R.id.tvAvgSub)

        barChart    = view.findViewById(R.id.barChart)
        rvPeakHours = view.findViewById(R.id.rvPeakHours)

        rvPeakHours.layoutManager = LinearLayoutManager(requireContext())
        rvPeakHours.isNestedScrollingEnabled = false

        adapter = RankedRowAdapter()
        rvPeakHours.adapter = adapter
        tvBreakdownMore.setOnClickListener { adapter.toggleExpanded(); updateMoreButton() }

        setupChartChrome()

        syncFilterFromActivity()
        loadPeakHours()
    }

    // ── Filter ─────────────────────────────────────────────────────────────

    override fun onFilterChanged(filter: ReportFilter, startDate: String?, endDate: String?) {
        currentFilter   = filter
        customStartDate = startDate
        customEndDate   = endDate
        loadPeakHours()
    }

    private fun syncFilterFromActivity() {
        (activity as? com.example.easy_billing.ReportsActivity)?.let {
            currentFilter   = it.currentFilter
            customStartDate = it.customStart
            customEndDate   = it.customEnd
        }
    }

    // ── Data loading ───────────────────────────────────────────────────────

    private var loadJob: kotlinx.coroutines.Job? = null

    private fun loadPeakHours() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val token = requireActivity()
                .getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {
                val calendar = AppTime.calendar()
                var start: String? = null
                var end: String?   = null
                var type = ""

                when (currentFilter) {
                    ReportFilter.TODAY -> type = "today"

                    ReportFilter.WEEK -> {
                        calendar.add(Calendar.DAY_OF_MONTH,
                            -(calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
                        start = sdf.format(calendar.time)
                        calendar.add(Calendar.DAY_OF_MONTH, 6)
                        end   = sdf.format(calendar.time)
                        type  = "custom"
                    }

                    ReportFilter.MONTH -> {
                        calendar.set(Calendar.DAY_OF_MONTH, 1)
                        start = sdf.format(calendar.time)
                        calendar.set(Calendar.DAY_OF_MONTH,
                            calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
                        end   = sdf.format(calendar.time)
                        type  = "custom"
                    }

                    ReportFilter.YEAR -> {
                        calendar.set(Calendar.MONTH, Calendar.JANUARY)
                        calendar.set(Calendar.DAY_OF_MONTH, 1)
                        start = sdf.format(calendar.time)
                        calendar.set(Calendar.MONTH, Calendar.DECEMBER)
                        calendar.set(Calendar.DAY_OF_MONTH, 31)
                        end   = sdf.format(calendar.time)
                        type  = "custom"
                    }

                    ReportFilter.CUSTOM -> {
                        if (customStartDate != null && customEndDate != null) {
                            start = customStartDate
                            end   = customEndDate
                            type  = "custom"
                        } else return@launch
                    }
                }

                val peak = RetrofitClient.api.getPeakHours(token, type, start, end)
                currentData = peak

                render(peak)

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                view?.let { v ->
                    com.google.android.material.snackbar.Snackbar
                        .make(v, R.string.peak_hours_toast_load_failed, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.reports_retry) { loadPeakHours() }
                        .show()
                }
            }
        }
    }

    // ── Render ─────────────────────────────────────────────────────────────

    private data class HPt(val hour: Int, val rev: Double, val bills: Int)
    private var axisPts: List<HPt> = emptyList()

    private fun render(data: List<PeakHourResponse>) {
        val ctx = requireContext()

        val map = data.associateBy { it.hour }
        // All 24 hours so the morning / afternoon / evening bands line up.
        val pts = (0..23).map { h -> HPt(h, map[h]?.revenue ?: 0.0, map[h]?.bills ?: 0) }
        val active = pts.filter { it.bills > 0 || it.rev > 0 }

        view?.findViewById<View>(R.id.tvPeakEmpty)?.visibility = if (active.isEmpty()) View.VISIBLE else View.GONE
        if (active.isEmpty()) {
            tvPeakRevenue.text = CurrencyHelper.format(ctx, 0.0)
            tvDelta.visibility = View.GONE
            tvBestValue.text = getString(R.string.dash); tvBestSub.text = getString(R.string.dash)
            tvAvgValue.text = getString(R.string.dash); tvAvgSub.text = getString(R.string.dash)
            barChart.clear(); barChart.invalidate()
            adapter.updateData(emptyList()); updateMoreButton()
            return
        }

        val best       = active.maxByOrNull { it.rev } ?: active.first()
        val totalRev   = active.sumOf { it.rev }
        val totalBills = active.sumOf { it.bills }
        val avgBills   = totalBills.toDouble() / active.size

        // Busiest-hour sales + share of the whole period
        tvPeakRevenue.text = CurrencyHelper.format(ctx, best.rev)
        if (totalRev > 0) {
            val pct = (best.rev / totalRev * 100).roundToInt()
            tvDelta.visibility = View.VISIBLE
            tvDelta.text = getString(R.string.busy_pct_of_total, pct)
            tvDelta.setTextColor(Color.parseColor("#085041"))
            tvDelta.background = GradientDrawable().apply {
                cornerRadius = dp(8f); setColor(Color.parseColor("#CFEBDD"))
            }
        } else {
            tvDelta.visibility = View.GONE
        }

        // Busiest / average boxes
        tvBestValue.text = formatRange(best.hour)
        tvBestSub.text   = getString(R.string.busy_best_sub, best.bills)
        tvAvgValue.text  = "%.1f".format(avgBills)
        tvAvgSub.text    = getString(R.string.busy_avg_sub, totalBills)

        drawBars(pts, best.hour)

        // Ranked breakdown (best first), only hours that had sales
        adapter.updateData(
            active.sortedByDescending { it.rev }.map { SalesRow(formatRange(it.hour), it.rev, it.bills) }
        )
        updateMoreButton()
    }

    private fun updateMoreButton() {
        if (!adapter.canExpand) { tvBreakdownMore.visibility = View.GONE; return }
        tvBreakdownMore.visibility = View.VISIBLE
        ivBreakdownMoreChevron.rotation = if (adapter.expanded) 180f else 0f
        tvBreakdownMoreText.text = if (adapter.expanded) getString(R.string.reports_show_less)
            else getString(R.string.reports_see_all_hours, adapter.totalCount)
    }

    private fun drawBars(pts: List<HPt>, peakHour: Int) {
        axisPts = pts
        // data = Pair(label, bills) so the tap bubble can show them
        val entries = pts.mapIndexed { i, p ->
            BarEntry(i.toFloat(), p.rev.toFloat(), Pair(formatRange(p.hour), p.bills))
        }
        val ds = BarDataSet(entries, "").apply {
            colors = pts.map { if (it.hour == peakHour) teal else blue }
            setDrawValues(false)
            highLightAlpha = 0
        }
        barChart.data = BarData(ds).apply { barWidth = 0.6f }

        val maxRev = pts.maxOf { it.rev }.toFloat().takeIf { it > 0f } ?: 1f
        barChart.axisLeft.axisMaximum  = maxRev * 1.30f
        barChart.axisRight.axisMaximum = maxRev * 1.30f

        (barChart as? DayPartBarChart)?.apply {
            showDayParts = true
            partLabels = listOf(
                getString(R.string.fragment_overview_busy_morning),
                getString(R.string.fragment_overview_busy_afternoon),
                getString(R.string.fragment_overview_busy_evening),
                getString(R.string.fragment_overview_busy_night)
            )
        }
        barChart.xAxis.apply {
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val i = value.roundToInt()
                    if (axisPts.getOrNull(i) == null) return ""
                    val visible = (barChart.highestVisibleX - barChart.lowestVisibleX + 1f).roundToInt().coerceIn(1, 24)
                    val step = if (visible > 16) 6 else if (visible > 8) 3 else 1
                    return if (i % step == 0) hourName(i) else ""
                }
            }
            textSize = 9.5f
            setLabelCount(pts.size, false)
        }
        barChart.fitScreen()
        barChart.setFitBars(true)
        barChart.renderer = RoundedBarChartRenderer(barChart, barChart.animator, barChart.viewPortHandler, 4f)
        barChart.animateY(600)
        barChart.invalidate()
    }

    /** One-time static bar-chart styling (matches the Trends tab). */
    private fun setupChartChrome() {
        barChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setScaleXEnabled(true)
            setScaleYEnabled(false)
            setPinchZoom(false)
            isDoubleTapToZoomEnabled = true
            isDragEnabled = true
            isDragXEnabled = true
            isDragYEnabled = false
            setDrawGridBackground(false)
            setTouchEnabled(true)
            isHighlightPerTapEnabled = true
            isHighlightPerDragEnabled = false
            setNoDataText("")
            marker = BarBubbleMarker(requireContext())
            setExtraOffsets(4f, 18f, 4f, 4f)
            axisRight.isEnabled = false
            axisLeft.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisRight.axisMinimum = 0f
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                setDrawAxisLine(true)
                axisLineColor = Color.parseColor("#D5DDD8")
                granularity = 1f
                textColor = Color.parseColor("#7C8782")
                yOffset = 6f
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ── Helpers ────────────────────────────────────────────────────────────

    /** 12-hour clock text, e.g. 6 AM, 6 PM. */
    private fun hourName(hour: Int): String {
        val x = ((hour % 24) + 24) % 24
        val n = if (x % 12 == 0) 12 else x % 12
        return "$n " + getString(if (x < 12) R.string.scrap_am else R.string.scrap_pm)
    }

    /** e.g. "6 PM – 7 PM". */
    private fun formatRange(hour: Int): String = hourName(hour) + " – " + hourName((hour + 1) % 24)

}
