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
    private lateinit var tvPerformanceScore: TextView
    private lateinit var tvStrategyTitle: TextView
    private lateinit var tvStrategyContent: TextView
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
        tvPerformanceScore = findViewById(R.id.tvPerformanceScore)
        tvStrategyTitle = findViewById(R.id.tvStrategyTitle)
        tvStrategyContent = findViewById(R.id.tvStrategyContent)
        tableProducts = findViewById(R.id.tableProducts)

        headerProduct = findViewById(R.id.headerProduct)
        headerQty = findViewById(R.id.headerQty)
        headerRevenue = findViewById(R.id.headerRevenue)

        setupSorting()
        
        // Initial entrance animations
        applyCascadingAnimations()
        
        // Start live pulse for hero indicator
        startHeroPulse()

        loadAiReport()
    }

    private fun applyCascadingAnimations() {
        val anim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.bento_card_enter)
        
        findViewById<android.view.View>(R.id.bentoRow1).startAnimation(anim)
        
        anim.startOffset = 100
        findViewById<android.view.View>(R.id.cardAiHero).startAnimation(anim)
        
        anim.startOffset = 200
        findViewById<android.view.View>(R.id.bentoRow3).startAnimation(anim)
        
        anim.startOffset = 300
        findViewById<android.view.View>(R.id.bentoRow4).startAnimation(anim)
    }

    private fun startHeroPulse() {
        val pulse = android.view.animation.AlphaAnimation(1.0f, 0.3f)
        pulse.duration = 800
        pulse.repeatMode = android.view.animation.Animation.REVERSE
        pulse.repeatCount = android.view.animation.Animation.INFINITE
        findViewById<android.view.View>(R.id.viewHeroPulse).startAnimation(pulse)
    }

    private fun loadAiReport() {

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        lifecycleScope.launch {

            try {
                val db = AppDatabase.getDatabase(this@AiDashboardActivity)
                val response = RetrofitClient.api.getAiReport("Bearer $token")

                // ================= REVENUE =================
                reportData = response.report_data.toMutableList()
                val totalRevenue = reportData.sumOf { it.revenue }
                tvRevenue.text = "₹$totalRevenue"
                
                // Calculate pseudo-performance score
                val score = if (totalRevenue > 50000) 92 else if (totalRevenue > 10000) 85 else 74
                tvPerformanceScore.text = score.toString()

                renderTable(reportData)

                // ================= PARSE AI REPORT INTO BENTO =================
                val rawText = response.ai_report
                val sections = parseAiSections(rawText)

                // Strategic Hero Card
                val strategy = sections["INVENTORY STRATEGY"] ?: sections["PROFIT STRATEGY"] ?: "Steady growth observed. Continue current operations."
                tvStrategyContent.text = strategy.replace("&lt;", "<").replace("&gt;", ">").trim()

                // Local Pulse Card (Everything else)
                val marketing = sections["MARKETING IDEAS"] ?: ""
                val sales = sections["SALES INSIGHTS"] ?: ""
                val localInsights = generateLocalInsights(db)
                
                val combinedPulse = "<b>Marketing:</b><br>$marketing<br><br><b>Sales Insights:</b><br>$sales<br><br><b>Store Alerts:</b><br>${localInsights.joinToString("<br>") { "• $it" }}"
                
                tvAiInsights.text = Html.fromHtml(
                    combinedPulse.replace("\n", "<br>"),
                    Html.FROM_HTML_MODE_LEGACY
                )

            } catch (e: Exception) {
                Toast.makeText(this@AiDashboardActivity, "Intelligence update in progress...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun parseAiSections(text: String): Map<String, String> {
        val sections = mutableMapOf<String, String>()
        val lines = text.lines()
        var currentHeader = ""
        var currentContent = StringBuilder()

        for (line in lines) {
            val upperLine = line.uppercase().trim()
            if (upperLine.contains("PRODUCTS") || upperLine.contains("STRATEGY") || upperLine.contains("INSIGHTS") || upperLine.contains("IDEAS") || upperLine.contains("RECOMMENDATION")) {
                if (currentHeader.isNotEmpty()) {
                    sections[currentHeader] = currentContent.toString()
                }
                currentHeader = upperLine.replace("📦", "").replace("💰", "").replace("🚀", "").trim()
                currentContent = StringBuilder()
            } else {
                currentContent.append(line).append("\n")
            }
        }
        if (currentHeader.isNotEmpty()) {
            sections[currentHeader] = currentContent.toString()
        }
        return sections
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
            insights.add("${it.key} is your best selling product")
        }

        // ================= PROFIT =================
        val profitMap = billItems
            .groupBy { it.productName }
            .mapValues { it.value.sumOf { it.profit } }

        val bestProfit = profitMap.maxByOrNull { it.value }
        val worstProfit = profitMap.minByOrNull { it.value }

        bestProfit?.let {
            insights.add("${it.key} gives highest profit")
        }

        worstProfit?.let {
            if (it.value < 0) {
                insights.add("${it.key} is causing loss")
            }
        }

        // ================= LOW STOCK =================
        inventory.forEach {

            if (it.currentStock <= 5) {
                insights.add("Low stock alert for Product ID ${it.productId}")
            }
        }

        return insights.take(5)
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
