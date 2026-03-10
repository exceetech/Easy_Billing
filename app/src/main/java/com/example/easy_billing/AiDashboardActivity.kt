package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.ProductReport
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch

class AiDashboardActivity : BaseActivity() {

    private lateinit var tvRevenue: TextView
    private lateinit var tvAiInsights: TextView
    private lateinit var tableProducts: TableLayout

    private lateinit var headerProduct: TextView
    private lateinit var headerQty: TextView
    private lateinit var headerRevenue: TextView

    private var reportData = mutableListOf<ProductReport>()

    private var productAsc = true
    private var qtyAsc = true
    private var revenueAsc = true

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_dashboard)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = "AI Business Insights"

        tvRevenue = findViewById(R.id.tvRevenue)
        tvAiInsights = findViewById(R.id.tvAiInsights)
        tableProducts = findViewById(R.id.tableProducts)

        headerProduct = findViewById(R.id.headerProduct)
        headerQty = findViewById(R.id.headerQty)
        headerRevenue = findViewById(R.id.headerRevenue)

        setupSorting()

        loadAiReport()
    }

    private fun loadAiReport() {

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        lifecycleScope.launch {

            try {

                val response = RetrofitClient.api.getAiReport("Bearer $token")

                reportData = response.report_data.toMutableList()

                val totalRevenue = response.report_data.sumOf { it.revenue }

                tvRevenue.text = "₹$totalRevenue"

                renderTable(response.report_data)

                var text = response.ai_report

                // Fix escaped HTML if model returns it
                text = text.replace("&lt;", "<")
                text = text.replace("&gt;", ">")

// Convert new lines to HTML
                text = text.replace("\n", "<br>")

                tvAiInsights.text = Html.fromHtml(
                    text,
                    Html.FROM_HTML_MODE_LEGACY
                )

            } catch (e: Exception) {

                Toast.makeText(
                    this@AiDashboardActivity,
                    "Failed to load AI report",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupSorting() {

        headerProduct.setOnClickListener {

            reportData = if (productAsc)
                reportData.sortedBy { it.product }.toMutableList()
            else
                reportData.sortedByDescending { it.product }.toMutableList()

            productAsc = !productAsc
            renderTable(reportData)
        }

        headerQty.setOnClickListener {

            reportData = if (qtyAsc)
                reportData.sortedBy { it.quantity }.toMutableList()
            else
                reportData.sortedByDescending { it.quantity }.toMutableList()

            qtyAsc = !qtyAsc
            renderTable(reportData)
        }

        headerRevenue.setOnClickListener {

            reportData = if (revenueAsc)
                reportData.sortedBy { it.revenue }.toMutableList()
            else
                reportData.sortedByDescending { it.revenue }.toMutableList()

            revenueAsc = !revenueAsc
            renderTable(reportData)
        }
    }

    private fun renderTable(data: List<ProductReport>) {

        if (tableProducts.childCount > 1) {
            tableProducts.removeViews(1, tableProducts.childCount - 1)
        }

        data.take(10).forEachIndexed { index, item ->

            val row = TableRow(this)

            if (index % 2 == 0) {
                row.setBackgroundColor(Color.parseColor("#F9FAFB"))
            } else {
                row.setBackgroundColor(Color.WHITE)
            }

            val product = TextView(this)
            product.layoutParams =
                TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 2f)
            product.text = item.product
            product.textSize = 16f
            product.setPadding(16,14,16,14)

            val qty = TextView(this)
            qty.layoutParams =
                TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            qty.text = item.quantity.toString()
            qty.textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            qty.textSize = 16f

            val revenue = TextView(this)
            revenue.layoutParams =
                TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            revenue.text = "₹${item.revenue}"
            revenue.textAlignment = TextView.TEXT_ALIGNMENT_TEXT_END
            revenue.textSize = 16f
            revenue.setTextColor(Color.parseColor("#059669"))

            row.addView(product)
            row.addView(qty)
            row.addView(revenue)

            tableProducts.addView(row)
        }
    }
}
