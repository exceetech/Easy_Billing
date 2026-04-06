package com.example.easy_billing.fragments

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.Filterable
import com.example.easy_billing.R
import com.example.easy_billing.ReportFilter
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.CurrencyHelper
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class OverviewFragment : Fragment(R.layout.fragment_overview), Filterable {

    private lateinit var tvRevenue: TextView
    private lateinit var tvBills: TextView
    private lateinit var tvAverage: TextView

    private lateinit var tvRevenueGrowth: TextView
    private lateinit var tvBillsGrowth: TextView
    private lateinit var tvAverageGrowth: TextView

    private lateinit var chartRevenueMini: LineChart
    private lateinit var chartBillsMini: LineChart
    private lateinit var chartAvgMini: LineChart

    private var currentFilter = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvRevenue = view.findViewById(R.id.tvRevenue)
        tvBills = view.findViewById(R.id.tvBills)
        tvAverage = view.findViewById(R.id.tvAverage)

        tvRevenueGrowth = view.findViewById(R.id.tvRevenueGrowth)
        tvBillsGrowth = view.findViewById(R.id.tvBillsGrowth)
        tvAverageGrowth = view.findViewById(R.id.tvAverageGrowth)

        chartRevenueMini = view.findViewById(R.id.chartRevenueMini)
        chartBillsMini = view.findViewById(R.id.chartBillsMini)
        chartAvgMini = view.findViewById(R.id.chartAvgMini)

        loadKPI()
    }

    override fun onFilterChanged(
        filter: ReportFilter,
        startDate: String?,
        endDate: String?
    ) {
        currentFilter = filter
        customStartDate = startDate
        customEndDate = endDate
        loadKPI()
    }

    private fun loadKPI() {

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
                        // ✅ SAFETY FIX ONLY
                        if (customStartDate != null && customEndDate != null) {
                            start = customStartDate
                            end = customEndDate
                            type = "custom"
                        } else {
                            return@launch
                        }
                    }
                }

                val avg = RetrofitClient.api.getAverageBill(
                    "Bearer $token",
                    type,
                    start,
                    end
                )

                val context = requireContext()

                // 🔥 KPI VALUES
                tvRevenue.text = CurrencyHelper.format(context, avg.total_revenue)
                tvBills.text = avg.total_bills.toString()
                tvAverage.text = CurrencyHelper.format(context, avg.average_bill)

                // 🔥 MINI CHARTS (FIX: clear before set)
                setupMiniChart(
                    chartRevenueMini,
                    listOf(avg.prev_revenue.toFloat(), avg.total_revenue.toFloat()),
                    getTrendColor(avg.total_revenue, avg.prev_revenue)
                )

                setupMiniChart(
                    chartBillsMini,
                    listOf(avg.prev_bills.toFloat(), avg.total_bills.toFloat()),
                    getTrendColor(avg.total_bills.toDouble(), avg.prev_bills.toDouble())
                )

                setupMiniChart(
                    chartAvgMini,
                    listOf(avg.prev_avg.toFloat(), avg.average_bill.toFloat()),
                    getTrendColor(avg.average_bill, avg.prev_avg)
                )

                // 🔥 GROWTH
                setGrowth(tvRevenueGrowth, avg.total_revenue, avg.prev_revenue)
                setGrowth(tvBillsGrowth, avg.total_bills.toDouble(), avg.prev_bills.toDouble())
                setGrowth(tvAverageGrowth, avg.average_bill, avg.prev_avg)

            } catch (e: Exception) {

                e.printStackTrace()

                Toast.makeText(
                    requireContext(),
                    "Failed to load overview",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setGrowth(view: TextView, current: Double, previous: Double) {

        if (previous == 0.0) {
            view.text = "0%"
            return
        }

        val change = ((current - previous) / previous) * 100
        val isPositive = change >= 0

        view.text = if (isPositive)
            "↑ ${"%.1f".format(change)}%"
        else
            "↓ ${"%.1f".format(kotlin.math.abs(change))}%"

        val color = if (isPositive)
            requireContext().getColor(R.color.green)
        else
            requireContext().getColor(R.color.red)

        view.setTextColor(color)

        view.setBackgroundResource(
            if (isPositive)
                R.drawable.bg_growth_positive
            else
                R.drawable.bg_growth_negative
        )

        view.setPadding(12, 4, 12, 4)
    }

    private fun getTrendColor(current: Double, previous: Double): Int {
        return if (current >= previous)
            requireContext().getColor(R.color.green)
        else
            requireContext().getColor(R.color.red)
    }

    private fun setupMiniChart(chart: LineChart, values: List<Float>, color: Int) {

        chart.clear() // ✅ IMPORTANT FIX

        val entries = values.mapIndexed { index, v ->
            Entry(index.toFloat(), v)
        }

        val dataSet = LineDataSet(entries, "")

        dataSet.color = color
        dataSet.lineWidth = 2.5f
        dataSet.setDrawCircles(false)
        dataSet.setDrawValues(false)

        dataSet.setDrawFilled(true)
        dataSet.fillDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color, android.graphics.Color.TRANSPARENT)
        )

        chart.data = LineData(dataSet)

        chart.description.isEnabled = false
        chart.legend.isEnabled = false

        chart.axisLeft.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.xAxis.isEnabled = false

        chart.setTouchEnabled(false)

        chart.animateX(500)
        chart.invalidate()
    }
}