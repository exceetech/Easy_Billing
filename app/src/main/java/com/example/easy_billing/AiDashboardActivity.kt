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
import androidx.core.graphics.toColorInt
import androidx.core.content.edit
import com.example.easy_billing.db.AppDatabase

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
        supportActionBar?.title = " "

        tvRevenue = findViewById(R.id.tvRevenue)
        tvAiInsights = findViewById(R.id.tvAiInsights)
        tableProducts = findViewById(R.id.tableProducts)

        headerProduct = findViewById(R.id.headerProduct)
        headerQty = findViewById(R.id.headerQty)
        headerRevenue = findViewById(R.id.headerRevenue)

        setupSorting()

        // 🔥 CHECK RESET FLAG FIRST
        val isReset = getSharedPreferences("app_settings", MODE_PRIVATE)
            .getBoolean("ai_reset", false)

        if (isReset) {
            forceClearUi()

            // remove flag after use
            getSharedPreferences("app_settings", MODE_PRIVATE)
                .edit {
                    putBoolean("ai_reset", false)
                }

        } else {
            loadAiReport()
        }
    }

    private fun loadAiReport() {

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        lifecycleScope.launch {

            try {

                val db = AppDatabase.getDatabase(this@AiDashboardActivity)

                val response = RetrofitClient.api.getAiReport("Bearer $token")

                // ================= BACKEND DATA =================
                reportData = response.report_data.toMutableList()

                val totalRevenue = reportData.sumOf { it.revenue }
                tvRevenue.text = "₹$totalRevenue"

                renderTable(reportData)

                // ================= BACKEND AI TEXT =================
                var backendText = response.ai_report
                backendText = backendText.replace("&lt;", "<")
                backendText = backendText.replace("&gt;", ">")
                backendText = backendText.replace("\n", "<br>")

                // ================= LOCAL AI =================
                val localInsights = generateLocalInsights(db)

                val localText = if (localInsights.isEmpty()) {
                    ""
                } else {
                    "<br><br><b>Smart Insights:</b><br>" +
                            localInsights.joinToString("<br>") { "• $it" }
                }

                val finalText = backendText + localText

                tvAiInsights.text = Html.fromHtml(
                    finalText,
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


    private suspend fun generateLocalInsights(db: AppDatabase): List<String> {

        val insights = mutableListOf<String>()

        val billItems = db.billItemDao().getAllItems()
        val inventory = db.inventoryDao().getAll()

        if (billItems.isEmpty()) return emptyList()

        // ================= BEST SELLER =================
        val bestSeller = billItems
            .groupBy { it.productName }
            .mapValues { it.value.sumOf { item -> item.quantity } }
            .maxByOrNull { it.value }

        bestSeller?.let {
            insights.add("🔥 ${it.key} is your best selling product")
        }

        // ================= PROFIT =================
        val profitMap = billItems
            .groupBy { it.productName }
            .mapValues { it.value.sumOf { it.profit } }

        val bestProfit = profitMap.maxByOrNull { it.value }
        val worstProfit = profitMap.minByOrNull { it.value }

        bestProfit?.let {
            insights.add("💰 ${it.key} gives highest profit")
        }

        worstProfit?.let {
            if (it.value < 0) {
                insights.add("❌ ${it.key} is causing loss")
            }
        }

        // ================= LOW STOCK =================
        inventory.forEach {

            if (it.currentStock <= 5) {
                insights.add("📦 Low stock alert for Product ID ${it.productId}")
            }
        }

        return insights.take(5) // limit
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
                row.setBackgroundColor("#F9FAFB".toColorInt())
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
            revenue.setTextColor("#059669".toColorInt())

            row.addView(product)
            row.addView(qty)
            row.addView(revenue)

            tableProducts.addView(row)
        }
    }

    private fun forceClearUi() {

        // Reset revenue
        tvRevenue.text = "₹0"

        // Reset insights
        tvAiInsights.text = "Start selling to see AI insights 📊"

        // Clear table
        if (tableProducts.childCount > 1) {
            tableProducts.removeViews(1, tableProducts.childCount - 1)
        }

        // Clear data list
        reportData.clear()
    }
}
