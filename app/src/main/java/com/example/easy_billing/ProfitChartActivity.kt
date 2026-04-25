package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.example.easy_billing.db.ProductProfitRaw
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

class ProfitChartActivity : AppCompatActivity() {

    private lateinit var chart: BarChart

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profit_chart)

        chart = findViewById(R.id.barChart)

        // 🔥 Close button
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            finish()
        }

        // 🔥 Get data from intent
        val data = intent.getSerializableExtra("DATA") as? ArrayList<ProductProfitRaw>

        if (data != null) {
            setupChart(data)
        } else {
            chart.clear()
            chart.setNoDataText("No data available")
        }
    }

    private fun setupChart(data: List<ProductProfitRaw>) {

        if (data.isEmpty()) {
            chart.clear()
            chart.setNoDataText("No profit data")
            return
        }

        val topItems = data.take(10)

        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        topItems.forEachIndexed { index, item ->
            entries.add(BarEntry(index.toFloat(), item.profit.toFloat()))

            val label = if (item.variant.isNullOrBlank())
                item.productName
            else "${item.productName} (${item.variant})"

            labels.add(label.take(10))
        }

        chart.clear()

        // 🔥 COLOR BASED ON TREND
        val isUpTrend = if (entries.size >= 2) {
            entries.last().y >= entries[entries.lastIndex - 1].y
        } else true

        val barColor = if (isUpTrend)
            Color.parseColor("#22C55E")
        else
            Color.parseColor("#EF4444")

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
            labelCount = 5
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