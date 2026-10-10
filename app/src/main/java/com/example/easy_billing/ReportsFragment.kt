package com.example.easy_billing.fragments

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.Filterable
import com.example.easy_billing.R
import com.example.easy_billing.ReportFilter
import com.example.easy_billing.ReportsActivity
import com.example.easy_billing.adapter.SalesRow
import com.example.easy_billing.adapter.RankedRowAdapter
import com.example.easy_billing.network.DailyReportResponse
import com.example.easy_billing.network.MonthlyReportResponse
import com.example.easy_billing.network.PeakHourResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.BarBubbleMarker
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.DayPartBarChart
import com.example.easy_billing.util.RoundedBarChartRenderer
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Trends tab — one bar chart that follows the date chips at the top:
 *   Today  -> 24 hourly bars, can go back up to 7 days
 *   Week   -> Sunday..Saturday, can go back up to 4 weeks
 *   Month  -> every day of the month, can go back up to 12 months
 *   Year   -> 12 monthly bars, can go back up to 2 years
 *   Custom -> one bar per day of the chosen range
 */
class ReportsFragment : Fragment(R.layout.fragment_reports), Filterable {

    private val tan  = Color.parseColor("#B8895A")
    private val teal = Color.parseColor("#0F6E56")
    private val ghost = Color.parseColor("#EFE6D2")

    // Views
    private lateinit var btnPeriodPrev: TextView
    private lateinit var btnPeriodNext: TextView
    private lateinit var tvPeriodTitle: TextView
    private lateinit var tvPeriodSub: TextView
    private lateinit var tvTotalLabel: TextView
    private lateinit var tvTotalValue: TextView
    private lateinit var tvDelta: TextView
    private lateinit var barChart: BarChart
    private lateinit var tvBestLabel: TextView; private lateinit var tvBestValue: TextView; private lateinit var tvBestSub: TextView
    private lateinit var tvAvgLabel: TextView;  private lateinit var tvAvgValue: TextView;  private lateinit var tvAvgSub: TextView
    private lateinit var tvBreakdownLabel: TextView
    private lateinit var rvSales: RecyclerView
    private lateinit var adapter: RankedRowAdapter
    private lateinit var tvBreakdownCaption: TextView
    private lateinit var tvBreakdownMore: View
    private lateinit var tvBreakdownMoreText: TextView
    private lateinit var ivBreakdownMoreChevron: View
    private var lastUnit = Unit3.DAY

    // State
    private var filter = ReportFilter.TODAY
    private var customStart: String? = null
    private var customEnd: String? = null
    private var offset = 0                       // 0 = current period, 1 = one period back, ...
    private var dailyData: List<DailyReportResponse> = emptyList()
    private var monthlyData: List<MonthlyReportResponse> = emptyList()
    private val hourlyCache = HashMap<String, List<PeakHourResponse>>()
    private var renderJob: Job? = null
    private var axisMode = AxisMode.ALL
    private var axisPts: List<Pt> = emptyList()

    private enum class AxisMode { HOUR, MONTH_DAY, CUSTOM, ALL }

    private val iso      = AppTime.isoDate()
    private val eeeFmt   = AppTime.formatter("EEE")
    private val ddMmmFmt = AppTime.formatter("dd MMM")
    private val dayListFmt = AppTime.formatter("EEE, dd MMM yyyy")

    private data class Pt(
        val short: String,      // x-axis label ("" = hide)
        val full: String,       // tooltip / breakdown label
        val rev: Double,
        val bills: Int,
        val future: Boolean = false
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.example.easy_billing.util.UserEventLogger.logAction("ReportsFragment", "opened")

        btnPeriodPrev = view.findViewById(R.id.btnPeriodPrev)
        btnPeriodNext = view.findViewById(R.id.btnPeriodNext)
        tvPeriodTitle = view.findViewById(R.id.tvPeriodTitle)
        tvPeriodSub   = view.findViewById(R.id.tvPeriodSub)
        tvTotalLabel = view.findViewById(R.id.tvTotalLabel)
        tvTotalValue = view.findViewById(R.id.tvTotalValue)
        tvDelta      = view.findViewById(R.id.tvDelta)
        barChart     = view.findViewById(R.id.barChart)
        tvBestLabel  = view.findViewById(R.id.tvBestLabel); tvBestValue = view.findViewById(R.id.tvBestValue); tvBestSub = view.findViewById(R.id.tvBestSub)
        tvAvgLabel   = view.findViewById(R.id.tvAvgLabel);  tvAvgValue  = view.findViewById(R.id.tvAvgValue);  tvAvgSub  = view.findViewById(R.id.tvAvgSub)
        tvBreakdownLabel = view.findViewById(R.id.tvBreakdownLabel)
        tvBreakdownCaption = view.findViewById(R.id.tvBreakdownCaption)
        tvBreakdownMore = view.findViewById(R.id.tvBreakdownMore)
        tvBreakdownMoreText = view.findViewById(R.id.tvBreakdownMoreText)
        ivBreakdownMoreChevron = view.findViewById(R.id.ivBreakdownMoreChevron)
        tvBreakdownMore.setOnClickListener { adapter.toggleExpanded(); updateMoreButton() }
        rvSales = view.findViewById(R.id.rvSales)

        rvSales.layoutManager = LinearLayoutManager(requireContext())
        rvSales.isNestedScrollingEnabled = false
        adapter = RankedRowAdapter()
        rvSales.adapter = adapter

        // Pick up the chip that was selected before this tab was created.
        (activity as? ReportsActivity)?.let {
            filter = it.currentFilter; customStart = it.customStart; customEnd = it.customEnd
        }

        btnPeriodPrev.setOnClickListener { stepPeriod(+1) }   // older
        btnPeriodNext.setOnClickListener { stepPeriod(-1) }   // newer


        setupChartChrome()
        loadReports()
    }

    override fun onFilterChanged(filter: ReportFilter, startDate: String?, endDate: String?) {
        this.filter = filter
        customStart = startDate
        customEnd = endDate
        offset = 0
        if (isAdded && view != null) render()
    }

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
                if (e is kotlinx.coroutines.CancellationException) throw e
                showLoadError { loadReports() }
            }
        }
    }

    private fun showLoadError(retry: () -> Unit) {
        val v = view ?: return
        com.google.android.material.snackbar.Snackbar
            .make(v, R.string.reports_toast_load_failed, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.reports_retry) { retry() }
            .show()
    }

    // ── Period navigation ─────────────────────────────────────────────────────

    private fun maxOffset() = when (filter) {
        ReportFilter.TODAY -> 7
        ReportFilter.WEEK -> 4
        ReportFilter.MONTH -> 12
        ReportFilter.YEAR -> 2
        ReportFilter.CUSTOM -> 0
    }

    private fun stepPeriod(delta: Int) {
        offset = (offset + delta).coerceIn(0, maxOffset())
        render()
    }

    private fun updateNav(title: String, sub: String) {
        tvPeriodTitle.text = title
        tvPeriodSub.text = sub
        val max = maxOffset()
        val olderOk = offset < max
        val newerOk = offset > 0
        btnPeriodPrev.visibility = if (max == 0) View.INVISIBLE else View.VISIBLE
        btnPeriodNext.visibility = if (max == 0) View.INVISIBLE else View.VISIBLE
        btnPeriodPrev.alpha = if (olderOk) 1f else 0.3f; btnPeriodPrev.isEnabled = olderOk
        btnPeriodNext.alpha = if (newerOk) 1f else 0.3f; btnPeriodNext.isEnabled = newerOk
    }

    // ── Data helpers ──────────────────────────────────────────────────────────

    private fun dateMinusDays(days: Int): Calendar {
        val c = AppTime.calendar(); c.add(Calendar.DAY_OF_YEAR, -days); return c
    }

    /** 12-hour clock text, e.g. 6 AM, 6 PM. */
    private fun hourName(h: Int): String {
        val x = ((h % 24) + 24) % 24
        val n = if (x % 12 == 0) 12 else x % 12
        return "$n " + getString(if (x < 12) R.string.scrap_am else R.string.scrap_pm)
    }

    private suspend fun hourlyFor(daysBack: Int, token: String): List<PeakHourResponse> {
        val dateStr = iso.format(dateMinusDays(daysBack).time)
        if (daysBack > 0) hourlyCache[dateStr]?.let { return it }
        val data = if (daysBack == 0) RetrofitClient.api.getTodayHourlySales(token)
                   else RetrofitClient.api.getPeakHours(token, "custom", dateStr, dateStr)
        if (daysBack > 0) hourlyCache[dateStr] = data
        return data
    }

    private fun hourPoints(data: List<PeakHourResponse>, daysBack: Int): List<Pt> {
        val map = data.associateBy { it.hour }
        val nowHour = AppTime.calendar().get(Calendar.HOUR_OF_DAY)
        return (0..23).map { h ->
            val r = map[h]
            Pt(hourName(h), hourName(h) + " – " + hourName((h + 1) % 24),
               r?.revenue ?: 0.0, r?.bills ?: 0, future = daysBack == 0 && h > nowHour)
        }
    }

    /** Sunday (week start) of the week that is [off] weeks before this one. */
    private fun weekStart(off: Int): Calendar {
        val c = AppTime.calendar()
        c.add(Calendar.DAY_OF_YEAR, -((c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY + 7) % 7))
        c.add(Calendar.DAY_OF_YEAR, -7 * off)
        return c
    }

    private fun dayPoint(c: Calendar, short: String, map: Map<String, DailyReportResponse>, today: String): Pt {
        val ds = iso.format(c.time)
        val r = map[ds]
        val full = if (ds == today) getString(R.string.reports_breakdown_today_full_prefix) + " " + ddMmmFmt.format(c.time)
                   else dayListFmt.format(c.time)
        return Pt(short, full, r?.revenue ?: 0.0, r?.bills ?: 0, future = ds > today)
    }

    private fun weekPoints(off: Int): List<Pt> {
        val map = dailyData.associateBy { it.date }
        val today = AppTime.todayIso()
        val start = weekStart(off)
        return (0..6).map { i ->
            val c = start.clone() as Calendar; c.add(Calendar.DAY_OF_YEAR, i)
            dayPoint(c, eeeFmt.format(c.time), map, today)
        }
    }

    private fun monthStart(off: Int): Calendar {
        val c = AppTime.calendar()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.MONTH, -off)
        return c
    }

    private fun monthPoints(off: Int): List<Pt> {
        val map = dailyData.associateBy { it.date }
        val today = AppTime.todayIso()
        val start = monthStart(off)
        val days = start.getActualMaximum(Calendar.DAY_OF_MONTH)
        return (1..days).map { d ->
            val c = start.clone() as Calendar; c.set(Calendar.DAY_OF_MONTH, d)
            dayPoint(c, d.toString(), map, today)
        }
    }

    private fun customPoints(): List<Pt> {
        val s = customStart ?: return emptyList()
        val e = customEnd ?: return emptyList()
        val map = dailyData.associateBy { it.date }
        val today = AppTime.todayIso()
        val cal = AppTime.calendar()
        cal.time = iso.parse(s) ?: return emptyList()
        val end = iso.parse(e) ?: return emptyList()
        val list = ArrayList<Calendar>()
        while (!cal.time.after(end)) { list.add(cal.clone() as Calendar); cal.add(Calendar.DAY_OF_YEAR, 1) }
        return list.map { c -> dayPoint(c, ddMmmFmt.format(c.time), map, today) }
    }

    private fun yearPoints(off: Int): List<Pt> {
        val year = AppTime.calendar().get(Calendar.YEAR) - off
        val ymFmt    = AppTime.formatter("yyyy-MM")
        val monShort = AppTime.formatter("MMM")
        val monFull  = AppTime.formatter("MMM yyyy")
        val nowYm = ymFmt.format(AppTime.calendar().time)
        return (0..11).map { m ->
            val c = AppTime.calendar()
            c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.YEAR, year); c.set(Calendar.MONTH, m)
            val ym = ymFmt.format(c.time)
            val row = monthlyData.firstOrNull { it.month.startsWith(ym) }
            Pt(monShort.format(c.time), monFull.format(c.time), row?.revenue ?: 0.0, row?.bills ?: 0, future = ym > nowYm)
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun render() {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val ctx = context ?: return@launch
            val pts: List<Pt>
            val prevTotal: Double
            val deltaText: String
            val unit: Unit3          // best / avg / breakdown wording

            when (filter) {
                ReportFilter.TODAY -> {
                    val token = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return@launch
                    try {
                        pts = hourPoints(hourlyFor(offset, token), offset)
                        prevTotal = sameSpan(pts, hourPoints(hourlyFor(offset + 1, token), offset + 1))
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        showLoadError { render() }
                        return@launch
                    }
                    deltaText = getString(R.string.reports_delta_vs_previous_day)
                    unit = Unit3.HOUR; axisMode = AxisMode.HOUR
                    val c = dateMinusDays(offset)
                    updateNav(
                        when (offset) { 0 -> getString(R.string.reports_nav_today); 1 -> getString(R.string.reports_nav_yesterday)
                                        else -> AppTime.formatter("EEEE").format(c.time) },
                        AppTime.formatter("EEE, dd MMM yyyy").format(c.time)
                    )
                }
                ReportFilter.WEEK -> {
                    pts = weekPoints(offset); prevTotal = sameSpan(pts, weekPoints(offset + 1))
                    deltaText = getString(R.string.reports_delta_vs_previous_week); unit = Unit3.DAY; axisMode = AxisMode.ALL
                    val s = weekStart(offset); val e = s.clone() as Calendar; e.add(Calendar.DAY_OF_YEAR, 6)
                    updateNav(
                        when (offset) { 0 -> getString(R.string.reports_week_label_this); 1 -> getString(R.string.reports_week_label_last)
                                        else -> getString(R.string.reports_weeks_ago, offset) },
                        ddMmmFmt.format(s.time) + " – " + ddMmmFmt.format(e.time)
                    )
                }
                ReportFilter.MONTH -> {
                    pts = monthPoints(offset); prevTotal = sameSpan(pts, monthPoints(offset + 1))
                    deltaText = getString(R.string.reports_delta_vs_previous_month); unit = Unit3.DAY; axisMode = AxisMode.MONTH_DAY
                    val s = monthStart(offset); val e = s.clone() as Calendar; e.set(Calendar.DAY_OF_MONTH, s.getActualMaximum(Calendar.DAY_OF_MONTH))
                    updateNav(
                        when (offset) { 0 -> getString(R.string.reports_nav_this_month); 1 -> getString(R.string.reports_nav_last_month)
                                        else -> AppTime.formatter("MMMM yyyy").format(s.time) },
                        ddMmmFmt.format(s.time) + " – " + ddMmmFmt.format(e.time)
                    )
                }
                ReportFilter.YEAR -> {
                    pts = yearPoints(offset); prevTotal = sameSpan(pts, yearPoints(offset + 1))
                    deltaText = getString(R.string.reports_delta_vs_previous_year); unit = Unit3.MONTH; axisMode = AxisMode.ALL
                    val year = AppTime.calendar().get(Calendar.YEAR) - offset
                    updateNav(
                        when (offset) { 0 -> getString(R.string.reports_year_label_this); 1 -> getString(R.string.reports_year_label_last)
                                        else -> year.toString() },
                        year.toString()
                    )
                }
                ReportFilter.CUSTOM -> {
                    val sDate = customStart?.let { runCatching { iso.parse(it) }.getOrNull() }
                    val eDate = customEnd?.let { runCatching { iso.parse(it) }.getOrNull() }
                    if (sDate == null || eDate == null) { pts = emptyList(); prevTotal = 0.0; deltaText = ""; unit = Unit3.DAY; axisMode = AxisMode.CUSTOM }
                    else if (customStart == customEnd) {
                        // A single day behaves like Today: hourly bars, compared with the day before.
                        val token = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return@launch
                        val back = Math.round(((iso.parse(AppTime.todayIso())?.time ?: sDate.time) - sDate.time) / 86_400_000.0).toInt()
                        try {
                            pts = hourPoints(hourlyFor(back, token), back)
                            prevTotal = sameSpan(pts, hourPoints(hourlyFor(back + 1, token), back + 1))
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            showLoadError { render() }
                            return@launch
                        }
                        deltaText = getString(R.string.reports_delta_vs_previous_day)
                        unit = Unit3.HOUR; axisMode = AxisMode.HOUR
                    } else {
                        pts = customPoints()
                        // Compare with the same number of days right before the range.
                        val n = pts.size
                        val map = dailyData.associateBy { it.date }
                        val c = AppTime.calendar(); c.time = sDate
                        var sum = 0.0
                        repeat(n) { c.add(Calendar.DAY_OF_YEAR, -1); sum += map[iso.format(c.time)]?.revenue ?: 0.0 }
                        // Skip the comparison for days a partly-elapsed range hasn't reached yet.
                        prevTotal = if (pts.any { it.future }) {
                            val elapsedN = pts.count { !it.future }
                            val c2 = AppTime.calendar(); c2.time = sDate
                            var s2 = 0.0
                            repeat(elapsedN) { c2.add(Calendar.DAY_OF_YEAR, -1); s2 += map[iso.format(c2.time)]?.revenue ?: 0.0 }
                            s2
                        } else sum
                        deltaText = getString(R.string.reports_delta_vs_previous_period)
                        unit = Unit3.DAY; axisMode = AxisMode.CUSTOM
                    }
                    updateNav(
                        getString(R.string.reports_nav_custom),
                        (customStart?.let { runCatching { ddMmmFmt.format(iso.parse(it)!!) }.getOrNull() } ?: "") + " – " +
                        (customEnd?.let { runCatching { ddMmmFmt.format(iso.parse(it)!!) }.getOrNull() } ?: "")
                    )
                }
            }
            bind(pts, prevTotal, deltaText, unit)
        }
    }

    private enum class Unit3 { HOUR, DAY, MONTH }

    /**
     * Previous-period total over the same span that has elapsed so far — so a half-finished
     * week/month/day is compared with the same point of the previous one (as Overview does),
     * not with its full total. A finished period is compared in full.
     */
    private fun sameSpan(current: List<Pt>, previous: List<Pt>): Double {
        val n = current.count { !it.future }
        return previous.take(n).sumOf { it.rev }
    }

    private fun bind(pts: List<Pt>, prevTotal: Double, deltaText: String, unit: Unit3) {
        val ctx = requireContext()

        tvBestLabel.text = getString(when (unit) { Unit3.HOUR -> R.string.reports_legend_best_hour; Unit3.DAY -> R.string.reports_legend_best_day; Unit3.MONTH -> R.string.reports_legend_best_month })
        tvAvgLabel.text  = getString(when (unit) { Unit3.HOUR -> R.string.reports_metric_avg_hour_label; Unit3.DAY -> R.string.reports_metric_avg_day_label; Unit3.MONTH -> R.string.reports_metric_avg_month_label })
        lastUnit = unit
        tvBreakdownCaption.text = getString(when (unit) { Unit3.HOUR -> R.string.reports_caption_hours; Unit3.DAY -> R.string.reports_caption_days; Unit3.MONTH -> R.string.reports_caption_months })
        tvBreakdownLabel.text = getString(when (unit) { Unit3.HOUR -> R.string.reports_breakdown_hourly; Unit3.DAY -> R.string.reports_breakdown_daily; Unit3.MONTH -> R.string.reports_breakdown_monthly })

        val elapsed = pts.filter { !it.future }
        if (pts.isEmpty() || elapsed.isEmpty()) {
            tvTotalValue.text = CurrencyHelper.format(ctx, 0.0)
            tvDelta.visibility = View.GONE
            barChart.clear(); barChart.invalidate()
            tvBestValue.text = getString(R.string.dash); tvBestSub.text = getString(R.string.dash)
            tvAvgValue.text = getString(R.string.dash); tvAvgSub.text = getString(R.string.dash)
            adapter.updateData(emptyList()); updateMoreButton()
            return
        }

        val total      = pts.sumOf { it.rev }
        val totalBills = pts.sumOf { it.bills }
        val bestIdx    = pts.indices.filter { !pts[it].future }.maxByOrNull { pts[it].rev } ?: 0
        val best       = pts[bestIdx]
        val avg        = total / elapsed.size

        tvTotalValue.text = CurrencyHelper.format(ctx, total)
        if (prevTotal > 0) {
            val pct = ((total - prevTotal) / prevTotal * 100).roundToInt()
            val up  = pct >= 0
            tvDelta.visibility = View.VISIBLE
            tvDelta.text = (if (up) "↑ " else "↓ ") + "${abs(pct)}% $deltaText"
            tvDelta.setTextColor(Color.parseColor(if (up) "#085041" else "#791F1F"))
            tvDelta.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8f); setColor(Color.parseColor(if (up) "#CFEBDD" else "#FBEDED"))
            }
        } else {
            tvDelta.visibility = View.GONE
        }

        if (best.rev > 0) { tvBestValue.text = CurrencyHelper.format(ctx, best.rev); tvBestSub.text = best.full }
        else { tvBestValue.text = getString(R.string.dash); tvBestSub.text = getString(R.string.dash) }
        tvAvgValue.text = CurrencyHelper.format(ctx, avg)
        tvAvgSub.text   = getString(R.string.reports_bills_count, totalBills)

        drawBars(pts, if (best.rev > 0) bestIdx else -1)

        // Breakdown: ranked best first, skipping future slots (and empty hours).
        val ranked = pts.filter { !it.future && (unit != Unit3.HOUR || it.bills > 0) }
            .sortedByDescending { it.rev }
        adapter.updateData(ranked.map { SalesRow(it.full, it.rev, it.bills) })
        updateMoreButton()
    }

    private fun updateMoreButton() {
        if (!adapter.canExpand) { tvBreakdownMore.visibility = View.GONE; return }
        tvBreakdownMore.visibility = View.VISIBLE
        ivBreakdownMoreChevron.rotation = if (adapter.expanded) 180f else 0f
        tvBreakdownMoreText.text = if (adapter.expanded) getString(R.string.reports_show_less)
            else getString(when (lastUnit) {
                Unit3.HOUR -> R.string.reports_see_all_hours
                Unit3.DAY -> R.string.reports_see_all_days
                Unit3.MONTH -> R.string.reports_see_all_months
            }, adapter.totalCount)
    }

    private fun drawBars(pts: List<Pt>, bestIdx: Int) {
        val entries = pts.mapIndexed { i, p -> BarEntry(i.toFloat(), p.rev.toFloat(), Pair(p.full, p.bills)) }
        val ds = BarDataSet(entries, "").apply {
            colors = pts.indices.map { when { it == bestIdx -> teal; pts[it].future -> ghost; else -> tan } }
            setDrawValues(false)
            highLightAlpha = 0
        }
        barChart.data = BarData(ds).apply { barWidth = if (pts.size > 14) 0.7f else 0.6f }

        val maxRev = pts.maxOf { it.rev }.toFloat().takeIf { it > 0f } ?: 1f
        barChart.axisLeft.axisMaximum  = maxRev * 1.30f
        barChart.axisRight.axisMaximum = maxRev * 1.30f

        axisPts = pts
        (barChart as? DayPartBarChart)?.apply {
            showDayParts = axisMode == AxisMode.HOUR
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
                    val p = axisPts.getOrNull(i) ?: return ""
                    // Labels thin out when zoomed out and fill in as you zoom in.
                    val visible = (barChart.highestVisibleX - barChart.lowestVisibleX + 1f).roundToInt()
                        .coerceIn(1, axisPts.size.coerceAtLeast(1))
                    val show = when (axisMode) {
                        AxisMode.HOUR -> i % (if (visible > 16) 6 else if (visible > 8) 3 else 1) == 0
                        AxisMode.MONTH_DAY ->
                            if (visible > 20) (i == 0 || (i + 1) % 5 == 0)
                            else if (visible > 10) i % 2 == 0 else true
                        AxisMode.CUSTOM -> i % Math.ceil(visible / 6.0).toInt().coerceAtLeast(1) == 0
                        AxisMode.ALL -> true
                    }
                    return if (show) p.short else ""
                }
            }
            textSize = if (pts.size > 7) 9.5f else 11f
            setLabelCount(pts.size, false)
        }
        barChart.fitScreen()     // new period -> back to the full view
        barChart.setFitBars(true)
        barChart.renderer = RoundedBarChartRenderer(barChart, barChart.animator, barChart.viewPortHandler, 4f)
        barChart.animateY(500)
        barChart.invalidate()
    }

    /** One-time static chart styling. */
    private fun setupChartChrome() {
        barChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setScaleXEnabled(true)        // zoom along time only
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
            isAutoScaleMinMaxEnabled = false
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
                axisLineColor = Color.parseColor("#E4DCC8")
                granularity = 1f
                textColor = Color.parseColor("#9A8F79")
                yOffset = 6f
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
