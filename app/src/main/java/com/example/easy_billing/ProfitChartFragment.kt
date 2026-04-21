package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.example.easy_billing.db.ProductProfitRaw
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

class ProfitChartFragment : Fragment(R.layout.fragment_profit_chart) {

    private lateinit var chart: BarChart

    private var pendingData: List<ProductProfitRaw>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        chart = view.findViewById(R.id.barChart)

        pendingData?.let {
            setupChart(it)
            pendingData = null
        }
    }

    fun updateChart(data: List<ProductProfitRaw>) {
        if (::chart.isInitialized) {
            setupChart(data)
        } else {
            pendingData = data
        }
    }

    private fun setupChart(data: List<ProductProfitRaw>) {

        if (data.isEmpty()) {
            chart.clear()
            chart.setNoDataText("No profit data")
            return
        }

        val topItems = data.take(7)

        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        topItems.forEachIndexed { index, item ->
            entries.add(BarEntry(index.toFloat(), item.profit.toFloat()))

            val label = if (item.variant.isNullOrBlank())
                item.productName
            else "${item.productName} (${item.variant})"

            labels.add(label.take(8))
        }

        chart.clear()

        // 🔥 TREND COLOR
        val isUpTrend = if (entries.size >= 2) {
            entries.last().y >= entries[entries.lastIndex - 1].y
        } else true

        val barColor = if (isUpTrend)
            Color.parseColor("#22C55E") // green
        else
            Color.parseColor("#EF4444") // red

        val dataSet = BarDataSet(entries, "")
        dataSet.color = barColor
        dataSet.setDrawValues(false)

        val barData = BarData(dataSet)
        barData.barWidth = 0.55f

        chart.data = barData

        // ================= X AXIS =================
        chart.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            position = XAxis.XAxisPosition.BOTTOM

            setDrawAxisLine(true)
            axisLineColor = Color.parseColor("#374151")

            setDrawGridLines(false)

            textColor = Color.parseColor("#6B7280")
            textSize = 10f

            granularity = 1f
            labelRotationAngle = -25f
        }

        // ================= Y AXIS =================
        chart.axisLeft.apply {
            setDrawAxisLine(true)
            axisLineColor = Color.parseColor("#374151")

            setDrawGridLines(true)
            gridColor = Color.argb(25, 156, 163, 175)

            textColor = Color.parseColor("#6B7280")
            labelCount = 4
        }

        chart.axisRight.isEnabled = false

        // ================= CLEAN =================
        chart.description.isEnabled = false
        chart.legend.isEnabled = false

        // ================= INTERACTION =================
        chart.setTouchEnabled(true)
        chart.setScaleEnabled(true)

        chart.animateY(800)

        chart.invalidate()
    }
}