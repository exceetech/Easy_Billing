package com.example.easy_billing

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.AiInsight
import com.example.easy_billing.network.AiReportResponse
import com.example.easy_billing.network.ProductReport
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

class AiDashboardActivity : BaseActivity() {

    private lateinit var tvPerformanceScore: TextView
    private lateinit var tvScoreBadge: TextView
    private lateinit var viewAuditRing: View
    private lateinit var tvKpiUrgent: TextView
    private lateinit var tvKpiLeaks: TextView
    private lateinit var tvKpiWins: TextView
    private lateinit var rvInsights: RecyclerView
    private lateinit var tvInsightsSummary: TextView
    private lateinit var tvInsightsEmpty: TextView
    private lateinit var insightAdapter: AiInsightListAdapter
    private lateinit var tableProducts: TableLayout

    private lateinit var headerProduct: TextView
    private lateinit var headerQty: TextView
    private lateinit var headerRevenue: TextView

    private var reportData = mutableListOf<ProductReport>()

    private var productAsc = true
    private var qtyAsc = true
    private var revenueAsc = true

    // Indian-grouped currency, rounded to whole rupees (avoids raw Double artifacts).
    private val moneyFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale("en", "IN"))
    private fun money(value: Double): String = "₹" + moneyFormat.format(Math.round(value))

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_dashboard)

        setupToolbar(R.id.toolbar)
        // Hide the default action-bar title; the toolbar shows a custom title view instead.
        supportActionBar?.setDisplayShowTitleEnabled(false)

        tvPerformanceScore = findViewById(R.id.tvPerformanceScore)
        tvScoreBadge = findViewById(R.id.tvScoreBadge)
        viewAuditRing = findViewById(R.id.viewAuditRing)
        tvKpiUrgent = findViewById(R.id.tvKpiUrgent)
        tvKpiLeaks = findViewById(R.id.tvKpiLeaks)
        tvKpiWins = findViewById(R.id.tvKpiWins)
        findViewById<View>(R.id.btnScoreInfo).setOnClickListener { showAuditScoreInfo() }
        tvInsightsSummary = findViewById(R.id.tvInsightsSummary)
        tvInsightsEmpty = findViewById(R.id.tvInsightsEmpty)
        rvInsights = findViewById(R.id.rvInsights)
        insightAdapter = AiInsightListAdapter(this)
        rvInsights.layoutManager = LinearLayoutManager(this)
        rvInsights.adapter = insightAdapter
        tableProducts = findViewById(R.id.tableProducts)

        headerProduct = findViewById(R.id.headerProduct)
        headerQty = findViewById(R.id.headerQty)
        headerRevenue = findViewById(R.id.headerRevenue)

        setupSorting()

        // Initial entrance animations
        applyCascadingAnimations()

        loadAiReport()
    }

    private fun applyCascadingAnimations() {
        // A fresh Animation per view — a single instance can't be shared across views,
        // and mutating its startOffset mid-flight is unreliable.
        val ids = listOf(R.id.bentoRow1, R.id.bentoRow3, R.id.rvInsights)
        ids.forEachIndexed { index, id ->
            val anim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.bento_card_enter)
            anim.startOffset = (index * 100).toLong()
            findViewById<View>(id).startAnimation(anim)
        }
    }

    private fun loadAiReport() {

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        lifecycleScope.launch {

            showLoadingState()

            try {
                if (!token.isNullOrEmpty()) {
                    // Auth header is attached by AuthInterceptor, not passed here.
                    val response = RetrofitClient.api.getAiReport()
                    saveCachedReport(response)          // #18: remember the last good report
                    renderOnline(response)
                } else {
                    renderOffline()
                }
            } catch (e: CancellationException) {
                throw e          // never swallow coroutine cancellation (activity destroyed, etc.)
            } catch (e: Exception) {
                Log.e("AiDashboard", "AI report load failed", e)
                // #18: fall back to the last cached report instead of a blank error.
                val cached = loadCachedReport()
                if (cached != null) {
                    try {
                        renderOnline(cached)
                        Toast.makeText(
                            this@AiDashboardActivity,
                            "Showing last saved insights (offline)",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (inner: Exception) {
                        Log.e("AiDashboard", "Cached render failed", inner)
                        showErrorState()
                    }
                } else {
                    showErrorState()
                    Toast.makeText(
                        this@AiDashboardActivity,
                        "Couldn't load insights. Check your connection.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** Compute off the main thread, then write the report to the bento cards on Main. */
    private suspend fun renderOnline(response: AiReportResponse) {
        val ui = withContext(Dispatchers.Default) {
            val report = response.report_data
            // Real audit score: start at 100, penalise critical ("fire") and
            // warning ("leak") findings (counted across the full insight set). Floor at 40.
            val fires = response.insights.count { it.type.equals("fire", ignoreCase = true) }
            val leaks = response.insights.count { it.type.equals("leak", ignoreCase = true) }
            val score = (100 - fires * 15 - leaks * 8).coerceIn(40, 100)

            AiUiData(report, score, response.insights)
        }

        reportData = ui.report.toMutableList()
        // Reset sort direction so the first header tap after a reload is predictable.
        productAsc = true
        qtyAsc = true
        revenueAsc = true
        tvPerformanceScore.text = ui.score.toString()
        applyScoreBand(ui.score)
        renderTable(reportData)
        bindInsights(ui.insights)
    }

    /** Update the urgent/leaks/wins KPI tiles + the grouped list and its empty state. */
    private fun bindInsights(insights: List<AiInsight>) {
        insightAdapter.submit(insights)

        tvKpiUrgent.text = insights.count { it.type.equals("fire", ignoreCase = true) }.toString()
        tvKpiLeaks.text = insights.count { it.type.equals("leak", ignoreCase = true) }.toString()
        tvKpiWins.text = insights.count { it.type.equals("gold", ignoreCase = true) }.toString()
        tvInsightsSummary.text = ""

        val empty = insights.isEmpty()
        rvInsights.visibility = if (empty) View.GONE else View.VISIBLE
        tvInsightsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
    }

    /** No token → nothing to show; prompt to connect. */
    private fun renderOffline() {
        tvPerformanceScore.text = "--"
        viewAuditRing.backgroundTintList = ColorStateList.valueOf(getColor(R.color.ai_neutral))
        tvInsightsSummary.text = ""
        tvInsightsEmpty.text = "Insights need an active connection."
        bindInsights(emptyList())
    }

    // ── #18: lightweight offline cache of the last successful report (shop-scoped) ──
    private val gson by lazy { com.google.gson.Gson() }

    /** Cache key is scoped per shop so a failure never shows another shop's data (F2). */
    private fun cacheKey(): String {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val shopId = try {
            prefs.getString("SHOP_ID", null) ?: prefs.getInt("SHOP_ID", 0).toString()
        } catch (e: ClassCastException) {
            prefs.getInt("SHOP_ID", 0).toString()
        }
        return "last_report_$shopId"
    }

    private suspend fun saveCachedReport(response: AiReportResponse) = withContext(Dispatchers.IO) {
        try {
            getSharedPreferences("ai_cache", MODE_PRIVATE)
                .edit().putString(cacheKey(), gson.toJson(response)).apply()
        } catch (e: Exception) {
            Log.e("AiDashboard", "Cache save failed", e)
        }
    }

    /**
     * Loads and sanitises the cached report (off the main thread). Gson bypasses Kotlin
     * null-safety, so a stale or old-schema payload can leave nulls in non-null fields —
     * drop/repair them here (F3).
     */
    @Suppress("USELESS_ELVIS", "SENSELESS_COMPARISON")
    private suspend fun loadCachedReport(): AiReportResponse? = withContext(Dispatchers.IO) {
        val json = getSharedPreferences("ai_cache", MODE_PRIVATE)
            .getString(cacheKey(), null) ?: return@withContext null
        try {
            val cached = gson.fromJson(json, AiReportResponse::class.java)
                ?: return@withContext null
            cached.copy(
                report_data = (cached.report_data ?: emptyList())
                    .filterNot { (it.product as String?).isNullOrBlank() },
                insights = (cached.insights ?: emptyList())
                    .filterNot { (it.type as String?).isNullOrBlank() },
                ai_report = cached.ai_report ?: ""
            )
        } catch (e: Exception) {
            Log.e("AiDashboard", "Cache load failed", e)
            null
        }
    }

    /** Themed dialog explaining what the audit score means and its colour bands. */
    private fun showAuditScoreInfo() {
        val view = layoutInflater.inflate(R.layout.dialog_audit_score_info, null)
        val dialog = android.app.Dialog(this)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        view.findViewById<View>(R.id.btnScoreInfoGotIt).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** Maps the audit score to a labelled, colour-coded band on the ring + number + badge. */
    private fun applyScoreBand(score: Int) {
        val (label, fillRes, inkRes) = when {
            score >= 90 -> Triple("OPTIMUM", R.color.band_green_bg, R.color.band_green_ink)
            score >= 78 -> Triple("HEALTHY", R.color.band_green_bg, R.color.band_green_ink)
            score >= 66 -> Triple("FAIR", R.color.band_amber_bg, R.color.band_amber_ink)
            else -> Triple("NEEDS WORK", R.color.band_red_bg, R.color.band_red_ink)
        }
        val fill = getColor(fillRes)
        val ink = getColor(inkRes)
        tvScoreBadge.text = label
        tvScoreBadge.setTextColor(ink)
        tvScoreBadge.backgroundTintList = ColorStateList.valueOf(fill)
        tvPerformanceScore.setTextColor(ink)
        viewAuditRing.backgroundTintList = ColorStateList.valueOf(ink)
    }

    /** Shown while the report is being fetched, so the screen never sits on stale placeholders. */
    private fun showLoadingState() {
        tvPerformanceScore.text = "…"
        tvScoreBadge.text = "…"
        viewAuditRing.backgroundTintList = ColorStateList.valueOf(getColor(R.color.ai_neutral))
        tvKpiUrgent.text = "…"
        tvKpiLeaks.text = "…"
        tvKpiWins.text = "…"
        tvInsightsSummary.text = ""
        insightAdapter.submit(emptyList())
        rvInsights.visibility = View.GONE
        tvInsightsEmpty.visibility = View.GONE
    }

    /** Shown when the fetch fails — a clear, non-blank error instead of frozen placeholders. */
    private fun showErrorState() {
        tvPerformanceScore.text = "—"
        tvScoreBadge.text = "—"
        viewAuditRing.backgroundTintList = ColorStateList.valueOf(getColor(R.color.ai_neutral))
        tvKpiUrgent.text = "—"
        tvKpiLeaks.text = "—"
        tvKpiWins.text = "—"
        tvInsightsSummary.text = ""
        tvInsightsEmpty.text = "Couldn't load insights. Check your connection."
        insightAdapter.submit(emptyList())
        rvInsights.visibility = View.GONE
        tvInsightsEmpty.visibility = View.VISIBLE
        if (tableProducts.childCount > 1) {
            tableProducts.removeViews(1, tableProducts.childCount - 1)
        }
    }

    /** Everything computed off-thread before the main-thread UI write. */
    private data class AiUiData(
        val report: List<ProductReport>,
        val score: Int,
        val insights: List<AiInsight>
    )



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
        val rows = data.take(10)

        // Reuse existing row/cell views (header stays at index 0); only update text + bg.
        rows.forEachIndexed { index, item ->
            val childIndex = index + 1
            val row = if (childIndex < tableProducts.childCount) {
                tableProducts.getChildAt(childIndex) as TableRow
            } else {
                TableRow(this).also {
                    // Weights + gravity must match the header row exactly (2 / 1 / 1.2) or
                    // the columns drift out of alignment.
                    it.addView(makeCell(2f, android.view.Gravity.START, R.color.ai_text_primary))
                    it.addView(makeCell(1f, android.view.Gravity.CENTER, R.color.ai_text_muted))
                    it.addView(makeCell(1.2f, android.view.Gravity.END, R.color.ai_green))
                    tableProducts.addView(it)
                }
            }
            row.setBackgroundColor(if (index % 2 == 0) getColor(R.color.ai_row_alt) else Color.WHITE)
            (row.getChildAt(0) as TextView).text = item.product
            (row.getChildAt(1) as TextView).text = item.quantity.toString()
            (row.getChildAt(2) as TextView).text = money(item.revenue)
        }

        // Trim leftover rows from a previously larger dataset.
        val desired = rows.size + 1
        if (tableProducts.childCount > desired) {
            tableProducts.removeViews(desired, tableProducts.childCount - desired)
        }
    }

    private fun makeCell(weight: Float, gravityValue: Int, colorRes: Int): TextView =
        TextView(this).apply {
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight)
            textSize = 14f
            gravity = gravityValue
            setTextColor(getColor(colorRes))
            setPadding(0, 12, 0, 12)   // vertical only — no horizontal indent, so it lines up with the header
        }
}
