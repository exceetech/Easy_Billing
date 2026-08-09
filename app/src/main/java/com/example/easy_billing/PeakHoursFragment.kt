package com.example.easy_billing.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import com.example.easy_billing.adapter.SalesRow
import com.example.easy_billing.adapter.SalesRowAdapter
import com.example.easy_billing.network.PeakHourResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.BarValueMarker
import com.example.easy_billing.util.CurrencyHelper
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
    private lateinit var tvPeakAt: TextView

    private lateinit var tvBestValue: TextView
    private lateinit var tvBestSub: TextView
    private lateinit var tvAvgValue: TextView
    private lateinit var tvAvgSub: TextView

    // ── State ──────────────────────────────────────────────────────────────
    private lateinit var adapter: SalesRowAdapter
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

        tvPeakRevenue = view.findViewById(R.id.tvPeakRevenue)
        tvDelta       = view.findViewById(R.id.tvDelta)
        tvPeakAt      = view.findViewById(R.id.tvPeakAt)

        tvBestValue   = view.findViewById(R.id.tvBestValue)
        tvBestSub     = view.findViewById(R.id.tvBestSub)
        tvAvgValue    = view.findViewById(R.id.tvAvgValue)
        tvAvgSub      = view.findViewById(R.id.tvAvgSub)

        barChart    = view.findViewById(R.id.barChart)
        rvPeakHours = view.findViewById(R.id.rvPeakHours)

        rvPeakHours.layoutManager = LinearLayoutManager(requireContext())
        rvPeakHours.isNestedScrollingEnabled = false

        adapter = SalesRowAdapter(emptyList(), 0.0)
        rvPeakHours.adapter = adapter

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

    private fun loadPeakHours() {
        lifecycleScope.launch {
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
                e.printStackTrace()
                Toast.makeText(requireContext(), R.string.peak_hours_toast_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Render (Sales-style: hero · bars · metric cards · breakdown) ────────

    private fun render(data: List<PeakHourResponse>) {
        val ctx = requireContext()

        if (data.isEmpty()) {
            tvPeakRevenue.text = CurrencyHelper.format(ctx, 0.0)
            tvDelta.visibility = View.GONE
            tvPeakAt.text = getString(R.string.dash)
            tvBestValue.text = getString(R.string.dash); tvBestSub.text = getString(R.string.dash)
            tvAvgValue.text = getString(R.string.dash); tvAvgSub.text = getString(R.string.dash)
            barChart.clear(); barChart.invalidate()
            adapter.updateData(emptyList(), 0.0)
            return
        }

        val byHour     = data.sortedBy { it.hour }
        val best       = data.maxByOrNull { it.revenue } ?: byHour.first()
        val totalRev   = data.sumOf { it.revenue }
        val totalBills = data.sumOf { it.bills }
        val avgBills   = totalBills.toDouble() / data.size

        // Hero: peak revenue + share-of-period chip + "at <hour>"
        tvPeakRevenue.text = CurrencyHelper.format(ctx, best.revenue)
        tvPeakAt.text      = "at ${formatHour(best.hour)}"
        if (totalRev > 0) {
            val pct = (best.revenue / totalRev * 100).roundToInt()
            tvDelta.visibility = View.VISIBLE
            tvDelta.text = "$pct% of total"
            tvDelta.setTextColor(Color.parseColor("#085041"))
            tvDelta.background = GradientDrawable().apply {
                cornerRadius = dp(8f); setColor(Color.parseColor("#DDEEEE"))
            }
        } else {
            tvDelta.visibility = View.GONE
        }

        // Metric cards
        tvBestValue.text = formatHourShort(best.hour)
        tvBestSub.text   = "${best.bills} bills"
        tvAvgValue.text  = "%.1f".format(avgBills)
        tvAvgSub.text    = "$totalBills bills"

        // Hourly bar chart (chronological), peak hour highlighted teal
        drawBars(byHour, best.hour)

        // Hourly breakdown list (ranked by revenue, ★ on peak)
        val maxRev = data.maxOf { it.revenue }
        val rows = data.sortedByDescending { it.revenue }.map {
            SalesRow(
                label   = formatHour(it.hour),
                revenue = it.revenue,
                bills   = it.bills,
                isBest  = it.hour == best.hour
            )
        }
        adapter.updateData(rows, maxRev)
    }

    private fun drawBars(byHour: List<PeakHourResponse>, peakHour: Int) {
        // data = Pair(label, bills) so the tap marker can show them
        val entries = byHour.mapIndexed { i, p ->
            BarEntry(i.toFloat(), p.revenue.toFloat(), Pair(formatHour(p.hour), p.bills))
        }
        val ds = BarDataSet(entries, "").apply {
            colors = byHour.map { if (it.hour == peakHour) teal else blue }
            setDrawValues(false)
            highLightAlpha = 0
        }
        barChart.data = BarData(ds).apply { barWidth = 0.55f }

        val maxRev = byHour.maxOf { it.revenue }.toFloat().takeIf { it > 0f } ?: 1f
        barChart.axisLeft.axisMaximum  = maxRev * 1.30f
        barChart.axisRight.axisMaximum = maxRev * 1.30f

        barChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String =
                byHour.getOrNull(value.roundToInt())?.let { formatHourShort(it.hour) } ?: ""
        }
        barChart.setFitBars(true)
        barChart.renderer = RoundedBarChartRenderer(barChart, barChart.animator, barChart.viewPortHandler, 4f)
        barChart.animateY(700)
        barChart.invalidate()
    }

    /** One-time static bar-chart styling (matches Sales). */
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
                textColor = Color.parseColor("#9A8F79")
                textSize = 10f
                yOffset = 6f
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Compact hour for axis/metric, e.g. "7 PM", "12 AM". */
    private fun formatHourShort(hour: Int): String = when {
        hour == 0  -> "12 AM"
        hour < 12  -> "$hour AM"
        hour == 12 -> "12 PM"
        else       -> "${hour - 12} PM"
    }

    private fun formatHour(hour: Int): String = when {
        hour == 0  -> "12:00 AM"
        hour < 12  -> "${hour}:00 AM"
        hour == 12 -> "12:00 PM"
        else       -> "${hour - 12}:00 PM"
    }

}
