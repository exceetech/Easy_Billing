package com.example.easy_billing.fragments

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.Filterable
import com.example.easy_billing.R
import com.example.easy_billing.ReportFilter
import com.example.easy_billing.adapter.PeakHourAdapter
import com.example.easy_billing.network.PeakHourResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.BarChartMarker
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class PeakHoursFragment : Fragment(R.layout.fragment_peak_hours), Filterable {

    private lateinit var rvPeakHours: RecyclerView
    private lateinit var chartPeakHours: LineChart

    private var currentFilter = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvPeakHours   = view.findViewById(R.id.rvPeakHours)
        chartPeakHours = view.findViewById(R.id.chartPeakHours)

        rvPeakHours.layoutManager = LinearLayoutManager(requireContext())

        loadPeakHours()
    }

    override fun onFilterChanged(
        filter: ReportFilter,
        startDate: String?,
        endDate: String?
    ) {
        currentFilter   = filter
        customStartDate = startDate
        customEndDate   = endDate

        loadPeakHours()
    }

    private fun loadPeakHours() {

        lifecycleScope.launch {

            val token = requireActivity()
                .getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val calendar = Calendar.getInstance()

                var start: String? = null
                var end:   String? = null
                var type = ""

                when (currentFilter) {

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
                        if (customStartDate != null && customEndDate != null) {
                            start = customStartDate
                            end   = customEndDate
                            type  = "custom"
                        } else {
                            return@launch
                        }
                    }
                }

                val peak = RetrofitClient.api.getPeakHours(
                    "Bearer $token",
                    type,
                    start,
                    end
                )

                // List — sorted by revenue descending (unchanged)
                val sortedList = peak.sortedByDescending { it.revenue }
                rvPeakHours.adapter = PeakHourAdapter(sortedList)

                // Chart — revenue curve
                drawChart(peak)

            } catch (e: Exception) {

                e.printStackTrace()

                Toast.makeText(
                    requireContext(),
                    "Failed to load peak hours",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun drawChart(data: List<PeakHourResponse>) {

        chartPeakHours.clear()

        if (data.isEmpty()) {
            chartPeakHours.invalidate()
            return
        }

        val sorted = data.sortedBy { it.hour }

        // ── Revenue entries only ──────────────────────────────────────────────
        val revenueEntries = sorted.mapIndexed { index, item ->
            Entry(index.toFloat(), item.revenue.toFloat())
        }

        val labels = sorted.map { "${it.hour}:00" }

        // ── Dataset styling ───────────────────────────────────────────────────
        val primaryColor = ContextCompat.getColor(requireContext(), R.color.primarySoft)

        val revenueSet = LineDataSet(revenueEntries, "Revenue").apply {

            // Smooth cubic curve
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.2f

            // Line
            color = primaryColor
            lineWidth = 2.5f

            // Dots
            setCircleColor(primaryColor)
            circleRadius = 4f
            circleHoleRadius = 2f
            setDrawCircleHole(true)

            // Gradient fill under the curve
            setDrawFilled(true)
            fillAlpha = 80
            fillColor = primaryColor

            // No value labels on points
            setDrawValues(false)

            // Highlight line
            highLightColor = Color.parseColor("#FF9800")
            highlightLineWidth = 1.5f
            enableDashedHighlightLine(8f, 4f, 0f)

            axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
        }

        val lineData = LineData(revenueSet)
        chartPeakHours.data = lineData

        // ── Marker ────────────────────────────────────────────────────────────
        chartPeakHours.marker = BarChartMarker(requireContext())

        // ── Chart-level settings ──────────────────────────────────────────────
        chartPeakHours.apply {

            description.isEnabled = false
            legend.isEnabled      = false

            setTouchEnabled(true)
            setDragEnabled(true)
            setScaleEnabled(false)
            setPinchZoom(false)

            setDrawGridBackground(false)
            setBackgroundColor(Color.TRANSPARENT)

            extraBottomOffset = 8f

            animateX(900)
        }

        // ── X Axis ─────────────────────────────────────────────────────────────
        chartPeakHours.xAxis.apply {
            valueFormatter    = IndexAxisValueFormatter(labels)
            position          = XAxis.XAxisPosition.BOTTOM
            granularity       = 1f
            setDrawGridLines(false)
            textColor         = Color.parseColor("#6B7280")
            textSize          = 10f
            labelRotationAngle = -45f
        }

        // ── Left Axis (Revenue) ────────────────────────────────────────────────
        chartPeakHours.axisLeft.apply {
            axisMinimum = 0f
            setDrawGridLines(true)
            gridColor   = Color.parseColor("#F3F4F6")
            textColor   = Color.parseColor("#6B7280")
            textSize    = 10f
        }

        // ── Right Axis (disabled) ──────────────────────────────────────────────
        chartPeakHours.axisRight.isEnabled = false

        chartPeakHours.invalidate()
    }
}