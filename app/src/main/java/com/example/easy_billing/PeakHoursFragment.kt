package com.example.easy_billing.fragments

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class PeakHoursFragment : Fragment(R.layout.fragment_peak_hours), Filterable {

    private lateinit var rvPeakHours: RecyclerView
    private lateinit var chartPeakHours: BarChart

    private var currentFilter = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvPeakHours = view.findViewById(R.id.rvPeakHours)
        chartPeakHours = view.findViewById(R.id.chartPeakHours)

        rvPeakHours.layoutManager = LinearLayoutManager(requireContext())

        loadPeakHours()
    }

    override fun onFilterChanged(
        filter: ReportFilter,
        startDate: String?,
        endDate: String?
    ) {
        currentFilter = filter
        customStartDate = startDate
        customEndDate = endDate

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
                var end: String? = null
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
                        // ✅ SAFE CHECK (no logic change)
                        if (customStartDate != null && customEndDate != null) {
                            start = customStartDate
                            end = customEndDate
                            type = "custom"
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

                // 🔥 LIST (UNCHANGED)
                val sortedList = peak.sortedByDescending { it.revenue }
                rvPeakHours.adapter = PeakHourAdapter(sortedList)

                // 🔥 CHART
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

        chartPeakHours.clear() // ✅ prevents overlay bug

        if (data.isEmpty()) {
            chartPeakHours.invalidate()
            return
        }

        val revenueEntries = ArrayList<BarEntry>()
        val billsEntries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        val sorted = data.sortedBy { it.hour }

        sorted.forEachIndexed { index, item ->

            revenueEntries.add(BarEntry(index.toFloat(), item.revenue.toFloat()))
            billsEntries.add(BarEntry(index.toFloat(), item.bills.toFloat()))
            labels.add("${item.hour}:00")
        }

        val revenueSet = BarDataSet(revenueEntries, "")
        val billsSet = BarDataSet(billsEntries, "")

        revenueSet.axisDependency = YAxis.AxisDependency.LEFT
        billsSet.axisDependency = YAxis.AxisDependency.RIGHT

        revenueSet.color = requireContext().getColor(R.color.primarySoft)
        billsSet.color = requireContext().getColor(R.color.secondaryColor)

        revenueSet.setDrawValues(false)
        billsSet.setDrawValues(false)

        val barData = BarData(revenueSet, billsSet)

        // ✅ stable grouping (IMPORTANT)
        val barWidth = 0.4f
        val barSpace = 0.02f
        val groupSpace = 0.2f

        barData.barWidth = barWidth

        val groupWidth = barData.getGroupWidth(groupSpace, barSpace)

        chartPeakHours.data = barData
        chartPeakHours.xAxis.axisMinimum = 0f
        chartPeakHours.xAxis.axisMaximum = groupWidth * sorted.size

        chartPeakHours.groupBars(0f, groupSpace, barSpace)
        chartPeakHours.xAxis.setCenterAxisLabels(true)

        // 🔥 Marker
        chartPeakHours.marker = BarChartMarker(requireContext())

        // 🔥 UI STYLE (UNCHANGED)
        chartPeakHours.apply {

            description.isEnabled = false
            legend.isEnabled = false

            setTouchEnabled(true)
            setDragEnabled(true)
            setScaleEnabled(false)

            animateY(900)
        }

        // 🔥 X Axis
        chartPeakHours.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            setDrawGridLines(false)
        }

        // 🔥 LEFT Axis
        chartPeakHours.axisLeft.apply {
            axisMinimum = 0f
            setDrawGridLines(true)
        }

        // 🔥 RIGHT Axis
        chartPeakHours.axisRight.apply {
            axisMinimum = 0f
            setDrawGridLines(false)
        }

        chartPeakHours.invalidate()
    }
}