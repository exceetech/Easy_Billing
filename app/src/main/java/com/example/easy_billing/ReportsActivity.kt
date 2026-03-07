package com.example.easy_billing

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.DailyReportAdapter
import com.example.easy_billing.adapter.MonthlyReportAdapter
import com.example.easy_billing.adapter.PeakHourAdapter
import com.example.easy_billing.network.*
import kotlinx.coroutines.launch
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.*
import com.google.android.material.button.MaterialButton
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface

class ReportsActivity : BaseActivity() {

    private lateinit var tvRevenue: TextView
    private lateinit var tvBills: TextView
    private lateinit var tvAverage: TextView
    private lateinit var tvInsights: TextView

    private lateinit var rvDaily: RecyclerView
    private lateinit var rvMonthly: RecyclerView
    private lateinit var rvProducts: RecyclerView
    private lateinit var rvPeakHours: RecyclerView

    private lateinit var chartSalesTrend: LineChart
    private lateinit var chartPeakHours: BarChart

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_reports)

        // Setup professional toolbar with back arrow
        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        tvRevenue = findViewById(R.id.tvRevenue)
        tvBills = findViewById(R.id.tvBills)
        tvAverage = findViewById(R.id.tvAverage)
        tvInsights = findViewById(R.id.tvInsights)

        rvDaily = findViewById(R.id.rvDaily)
        rvMonthly = findViewById(R.id.rvMonthly)
        rvProducts = findViewById(R.id.rvProducts)
        rvPeakHours = findViewById(R.id.rvPeakHours)

        chartSalesTrend = findViewById(R.id.chartSalesTrend)
        chartPeakHours = findViewById(R.id.chartPeakHours)

        rvDaily.layoutManager = LinearLayoutManager(this)
        rvMonthly.layoutManager = LinearLayoutManager(this)
        rvProducts.layoutManager = LinearLayoutManager(this)
        rvPeakHours.layoutManager = LinearLayoutManager(this)

        findViewById<MaterialButton>(R.id.btnSendReport)
            .setOnClickListener { sendEmailReport() }

        loadReports()
    }

    // ================= LOAD REPORTS =================

    private fun loadReports() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            if (token == null) return@launch

            try {

                val daily = RetrofitClient.api.getDailyReport("Bearer $token")
                val monthly = RetrofitClient.api.getMonthlyReport("Bearer $token")
                val products = RetrofitClient.api.getTopProducts("Bearer $token")
                val peak = RetrofitClient.api.getPeakHours("Bearer $token")
                val avg = RetrofitClient.api.getAverageBill("Bearer $token")
                val trend = RetrofitClient.api.getSalesTrend("Bearer $token")

                // KPI cards

                tvRevenue.text = "₹ %.2f".format(avg.total_revenue)
                tvBills.text = avg.total_bills.toString()
                tvAverage.text = "₹ %.2f".format(avg.average_bill)

                // Tables

                rvDaily.adapter = DailyReportAdapter(daily)
                rvMonthly.adapter = MonthlyReportAdapter(monthly)
                rvProducts.adapter = ProductReportAdapter(products)
                rvPeakHours.adapter = PeakHourAdapter(peak)

                // Charts

                drawSalesTrend(trend)
                drawPeakHoursChart(peak)

                // Insights

                generateInsights(products, peak, daily)

            } catch (e: Exception) {

                e.printStackTrace()

                Toast.makeText(
                    this@ReportsActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ================= SALES TREND CHART =================

    private fun drawSalesTrend(trend: List<SalesTrendResponse>) {

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        trend.forEachIndexed { index, report ->
            entries.add(Entry(index.toFloat(), report.revenue.toFloat()))
            labels.add(report.date.substring(5)) // show MM-DD
        }

        val dataset = LineDataSet(entries, "Sales Trend")

        dataset.lineWidth = 3f
        dataset.circleRadius = 5f
        dataset.setDrawValues(false)

        dataset.color = resources.getColor(R.color.primaryColor, theme)
        dataset.setCircleColor(resources.getColor(R.color.primaryColor, theme))

        dataset.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataset.setDrawFilled(true)

        val gradient = resources.getDrawable(R.drawable.chart_gradient, theme)
        dataset.fillDrawable = gradient

        val lineData = LineData(dataset)

        chartSalesTrend.data = lineData

        chartSalesTrend.description.isEnabled = false
        chartSalesTrend.setTouchEnabled(true)
        chartSalesTrend.setPinchZoom(true)

        chartSalesTrend.animateX(1200)

        val xAxis = chartSalesTrend.xAxis
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        xAxis.granularity = 1f
        xAxis.position = XAxis.XAxisPosition.BOTTOM

        chartSalesTrend.axisRight.isEnabled = false

        chartSalesTrend.invalidate()
    }

    // ================= PEAK HOURS CHART =================

    private fun drawPeakHoursChart(data: List<PeakHourResponse>) {

        val entries = ArrayList<BarEntry>()

        data.forEach {
            entries.add(BarEntry(it.hour.toFloat(), it.revenue.toFloat()))
        }

        val dataset = BarDataSet(entries, "Peak Hour Revenue")

        dataset.colors = listOf(
            resources.getColor(R.color.primaryColor, theme),
            resources.getColor(R.color.secondaryColor, theme)
        )

        dataset.valueTextSize = 12f
        dataset.valueTextColor = resources.getColor(R.color.textPrimary, theme)

        val barData = BarData(dataset)
        barData.barWidth = 0.6f

        chartPeakHours.data = barData

        chartPeakHours.description.isEnabled = false
        chartPeakHours.animateY(1200)

        val xAxis = chartPeakHours.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.granularity = 1f

        chartPeakHours.axisRight.isEnabled = false

        chartPeakHours.invalidate()
    }

    // ================= EMAIL REPORT =================

    private fun sendEmailReport() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                RetrofitClient.api.sendEmailReport("Bearer $token")

                Toast.makeText(
                    this@ReportsActivity,
                    "Report sent to your email",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {

                Toast.makeText(
                    this@ReportsActivity,
                    "Failed to send email",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ================= TIME FORMAT =================

    private fun formatHour(hour: Int): String {

        val start = "%02d:00".format(hour)
        val end = "%02d:00".format((hour + 1) % 24)

        return "$start - $end"
    }

    // ================= INSIGHTS =================

    private fun generateInsights(
        products: List<TopProductResponse>,
        peak: List<PeakHourResponse>,
        daily: List<DailyReportResponse>
    ) {

        if(products.isEmpty() || peak.isEmpty() || daily.isEmpty()) {
            tvInsights.text = "Not enough data to generate insights."
            return
        }

        val topProduct = products.maxByOrNull { it.quantity }?.product ?: "N/A"
        val weakProduct = products.minByOrNull { it.quantity }?.product ?: "N/A"

        val peakHour = peak.maxByOrNull { it.revenue }?.hour ?: 0
        val slowHour = peak.minByOrNull { it.revenue }?.hour ?: 0

        val peakTime = formatHour(peakHour)
        val slowTime = formatHour(slowHour)

        val firstRevenue = daily.first().revenue
        val lastRevenue = daily.last().revenue

        val trend = when {
            lastRevenue > firstRevenue -> "Sales are increasing 📈"
            lastRevenue < firstRevenue -> "Sales are decreasing 📉"
            else -> "Sales are stable"
        }

        val builder = SpannableStringBuilder()

        builder.append("📊 BUSINESS INSIGHTS\n\n")

        builder.append("🏆 Best Selling Product\n")
        val startTop = builder.length
        builder.append("$topProduct\n\n")
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            startTop,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        builder.append("⚠ Weak Product\n")
        val startWeak = builder.length
        builder.append("$weakProduct\n\n")
        builder.setSpan(
            ForegroundColorSpan(resources.getColor(android.R.color.holo_red_dark)),
            startWeak,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        builder.append("🔥 Peak Sales Time\n")
        val startPeak = builder.length
        builder.append("$peakTime\n\n")
        builder.setSpan(
            ForegroundColorSpan(resources.getColor(android.R.color.holo_green_dark)),
            startPeak,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        builder.append("🐢 Slow Sales Time\n")
        val startSlow = builder.length
        builder.append("$slowTime\n\n")
        builder.setSpan(
            ForegroundColorSpan(resources.getColor(android.R.color.darker_gray)),
            startSlow,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        builder.append("📈 Sales Trend\n")
        builder.append("$trend\n\n")

        builder.append("💡 Recommendations\n")
        builder.append("• Promote $weakProduct with discounts\n")
        builder.append("• Stock more $topProduct during $peakTime\n")
        builder.append("• Run offers during slow hours\n")

        tvInsights.text = builder
    }
}