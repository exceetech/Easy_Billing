package com.example.easy_billing.fragments

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
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
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class PeakHoursFragment : Fragment(R.layout.fragment_peak_hours), Filterable {

    private lateinit var rvPeakHours: RecyclerView
    private lateinit var btnChart: ImageButton

    private lateinit var adapter: PeakHourAdapter

    private var currentFilter = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private var currentData: List<PeakHourResponse> = emptyList()

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvPeakHours = view.findViewById(R.id.rvPeakHours)
        btnChart = view.findViewById(R.id.btnChart)

        rvPeakHours.layoutManager = LinearLayoutManager(requireContext())

        adapter = PeakHourAdapter(emptyList())
        rvPeakHours.adapter = adapter

        btnChart.setOnClickListener {
            showChartDialog(currentData)
        }

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

                    ReportFilter.TODAY -> type = "today"

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
                        calendar.set(Calendar.DAY_OF_MONTH,
                            calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
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
                            end = customEndDate
                            type = "custom"
                        } else return@launch
                    }
                }

                val peak = RetrofitClient.api.getPeakHours(
                    "Bearer $token",
                    type,
                    start,
                    end
                )

                currentData = peak

                val sortedList = peak.sortedByDescending { it.revenue }
                adapter.updateData(sortedList)

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(),
                    "Failed to load peak hours",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================= FULL SCREEN CHART =================

    private fun showChartDialog(data: List<PeakHourResponse>) {

        if (data.isEmpty()) return

        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_peak_chart)

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val chart = dialog.findViewById<com.github.mikephil.charting.charts.BarChart>(R.id.chart)

        chart.clear()

        val sorted = data.sortedBy { it.hour }

        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        sorted.forEachIndexed { index, item ->
            entries.add(BarEntry(index.toFloat(), item.revenue.toFloat()))
            labels.add("${item.hour}:00")
        }

        if (entries.isEmpty()) {
            chart.setNoDataText("No data available")
            return
        }

        // ================= PEAK COLOR LOGIC =================
        val maxValue = entries.maxOfOrNull { it.y } ?: 0f
        val minValue = entries.minOfOrNull { it.y } ?: 0f

        val colors = entries.map { entry ->
            when (entry.y) {
                maxValue -> Color.parseColor("#22C55E") // 🟢 Peak
                minValue -> Color.parseColor("#EF4444") // 🔴 Low
                else -> Color.parseColor("#60A5FA")     // 🔵 Normal
            }
        }

        val dataSet = BarDataSet(entries, "").apply {

            setColors(colors)

            setDrawValues(false)

            // 🔥 highlight like ChartsFragment
            highLightColor = Color.argb(120, 0, 0, 0)
            highLightAlpha = 120
        }

        val barData = BarData(dataSet).apply {
            barWidth = 0.6f
        }

        chart.data = barData

        // ================= X AXIS =================
        chart.xAxis.apply {

            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {

                    val index = value.toInt()
                    if (index < 0 || index >= labels.size) return ""

                    val hour = labels[index].replace(":00", "").toInt()

                    return when {
                        hour == 0 -> "12am"
                        hour < 12 -> "${hour}am"
                        hour == 12 -> "12pm"
                        else -> "${hour - 12}pm"
                    }
                }
            }
            position = XAxis.XAxisPosition.BOTTOM

            // axis line like premium chart
            setDrawAxisLine(true)
            axisLineColor = Color.parseColor("#374151")
            axisLineWidth = 1.5f

            // grid style
            setDrawGridLines(true)
            gridColor = Color.argb(25, 156, 163, 175)
            enableGridDashedLine(6f, 6f, 0f)

            textColor = Color.parseColor("#6B7280")
            textSize = 10f

            granularity = 1f
        }

        // ================= Y AXIS =================
        chart.axisLeft.apply {

            axisMinimum = 0f

            setDrawAxisLine(true)
            axisLineColor = Color.parseColor("#374151")
            axisLineWidth = 1.5f

            setDrawGridLines(true)
            gridColor = Color.argb(25, 156, 163, 175)
            enableGridDashedLine(6f, 6f, 0f)

            textColor = Color.parseColor("#6B7280")
            textSize = 11f

            labelCount = 4
        }

        chart.axisRight.isEnabled = false

        // ================= CLEAN =================
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
        chart.marker = BarChartMarker(requireContext())

        // ================= SPACING =================
        chart.setExtraOffsets(8f, 16f, 8f, 12f)

        // ================= ANIMATION =================
        chart.animateY(900)

        chart.invalidate()

        dialog.findViewById<View>(R.id.btnClose).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
