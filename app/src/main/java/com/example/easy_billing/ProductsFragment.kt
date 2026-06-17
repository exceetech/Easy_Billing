package com.example.easy_billing.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.Filterable
import com.example.easy_billing.R
import com.example.easy_billing.ReportFilter
import com.example.easy_billing.adapter.ProductRow
import com.example.easy_billing.adapter.ProductRowAdapter
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.TopProductResponse
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.CurrencyHelper
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.roundToInt

/**
 * Top Products — Sales design language with a SHARE donut.
 * The sort (Fast moving / Revenue / Popular / Smart) drives the metric the
 * donut, center total, legend and breakdown list show.
 */
class ProductsFragment : Fragment(R.layout.fragment_products), Filterable {

    private lateinit var tvSortChip: TextView
    private lateinit var pieChart: PieChart
    private lateinit var tvCenterVal: TextView
    private lateinit var tvCenterLbl: TextView
    private lateinit var llLegend: LinearLayout
    private lateinit var rvProducts: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: ProductRowAdapter

    private var currentFilter = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private var sortBy = "quantity"   // quantity | revenue | frequency | smart

    private val sdf = AppTime.isoDate()
    private val sliceColors = listOf("#378ADD", "#1D9E75", "#E0921A", "#7F77DD", "#D85A30", "#B4B2A9")
        .map { Color.parseColor(it) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvSortChip  = view.findViewById(R.id.tvSortChip)
        pieChart    = view.findViewById(R.id.pieChart)
        tvCenterVal = view.findViewById(R.id.tvCenterVal)
        tvCenterLbl = view.findViewById(R.id.tvCenterLbl)
        llLegend    = view.findViewById(R.id.llLegend)
        rvProducts  = view.findViewById(R.id.rvProducts)
        tvEmpty     = view.findViewById(R.id.tvEmpty)

        rvProducts.layoutManager = LinearLayoutManager(requireContext())
        rvProducts.isNestedScrollingEnabled = false
        adapter = ProductRowAdapter()
        rvProducts.adapter = adapter

        setupPie()
        tvSortChip.text = sortLabel() + " ▾"
        tvSortChip.setOnClickListener { showSortMenu(it) }

        syncFilterFromActivity()
        loadProducts()
    }

    // ── Filter ───────────────────────────────────────────────────────────────

    override fun onFilterChanged(filter: ReportFilter, startDate: String?, endDate: String?) {
        currentFilter = filter
        customStartDate = startDate
        customEndDate = endDate
        loadProducts()
    }

    private fun syncFilterFromActivity() {
        (activity as? com.example.easy_billing.ReportsActivity)?.let {
            currentFilter = it.currentFilter
            customStartDate = it.customStart
            customEndDate = it.customEnd
        }
    }

    // ── Sort ─────────────────────────────────────────────────────────────────

    private fun sortLabel(key: String = sortBy): String = when (key) {
        "revenue"   -> "Revenue"
        "frequency" -> "Popular"
        "smart"     -> "Smart"
        else        -> "Fast moving"
    }

    private fun showSortMenu(anchor: View) {
        val keys = listOf("quantity", "revenue", "frequency", "smart")
        val selected = keys.indexOf(sortBy).coerceAtLeast(0)
        com.example.easy_billing.ui.ThemedDropdown.show(
            anchor, keys.map { sortLabel(it) }, selected, rightAlign = true
        ) { idx ->
            sortBy = keys[idx]
            tvSortChip.text = sortLabel() + " ▾"
            loadProducts()
        }
    }

    // ── Data loading ───────────────────────────────────────────────────────────

    private fun loadProducts() {
        lifecycleScope.launch {
            val token = requireActivity()
                .getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch
            try {
                var type = "today"
                var start: String? = null
                var end: String? = null
                val calendar = AppTime.calendar()

                when (currentFilter) {
                    ReportFilter.TODAY -> type = "today"
                    ReportFilter.WEEK -> {
                        calendar.add(Calendar.DAY_OF_MONTH,
                            -(calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
                        start = sdf.format(calendar.time)
                        calendar.add(Calendar.DAY_OF_MONTH, 6)
                        end = sdf.format(calendar.time)
                        type = "custom"
                    }
                    ReportFilter.MONTH -> {
                        calendar.set(Calendar.DAY_OF_MONTH, 1)
                        start = sdf.format(calendar.time)
                        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
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
                            start = customStartDate; end = customEndDate; type = "custom"
                        } else return@launch
                    }
                }

                val products = RetrofitClient.api.getTopProducts(token, type, start, end, sortBy)
                render(products)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ── Render ───────────────────────────────────────────────────────────────

    private fun metricOf(p: TopProductResponse): Double = when (sortBy) {
        "revenue", "smart" -> p.revenue
        "frequency"        -> p.frequency.toDouble()
        else               -> p.quantity
    }

    private fun render(products: List<TopProductResponse>) {
        val ctx = requireContext()
        if (products.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvProducts.visibility = View.GONE
            llLegend.removeAllViews()
            pieChart.clear()
            tvCenterVal.text = "—"
            return
        }
        tvEmpty.visibility = View.GONE
        rvProducts.visibility = View.VISIBLE

        val total = products.sumOf { metricOf(it) }
        tvCenterVal.text = centerValue(total)
        tvCenterLbl.text = centerLabel()

        // Donut: top 5 by metric + Others
        val byMetric = products.sortedByDescending { metricOf(it) }
        val top = byMetric.take(5)
        val othersVal = byMetric.drop(5).sumOf { metricOf(it) }
        val slices = top.map { name(it) to metricOf(it) }.toMutableList()
        if (othersVal > 0) slices.add("Others" to othersVal)

        val entries = slices.map { PieEntry(it.second.toFloat(), it.first) }
        val ds = PieDataSet(entries, "").apply {
            colors = (0 until slices.size).map { sliceColors[it % sliceColors.size] }
            sliceSpace = 2f
            setDrawValues(false)
        }
        pieChart.data = PieData(ds)
        pieChart.highlightValues(null)
        pieChart.invalidate()

        buildLegend(slices, total)

        // Breakdown list — backend's sort order; star the #1
        val rows = products.mapIndexed { i, p ->
            ProductRow(name(p), secondary(ctx, p), valueText(ctx, p), isBest = i == 0)
        }
        adapter.updateData(rows)
    }

    private fun setupPie() {
        pieChart.apply {
            setUsePercentValues(false)
            description.isEnabled = false
            legend.isEnabled = false
            setDrawEntryLabels(false)
            isRotationEnabled = false
            isHighlightPerTapEnabled = true
            setHoleColor(Color.WHITE)
            holeRadius = 68f
            transparentCircleRadius = 71f
            setDrawCenterText(false)   // custom overlay instead
            setExtraOffsets(2f, 2f, 2f, 2f)
        }
    }

    private fun buildLegend(slices: List<Pair<String, Double>>, total: Double) {
        llLegend.removeAllViews()
        val ctx = requireContext()
        val safeTotal = total.takeIf { it > 0 } ?: 1.0
        slices.indices.chunked(2).forEach { idxs ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(9) }
            }
            idxs.forEachIndexed { col, i ->
                val pct = (slices[i].second / safeTotal * 100).roundToInt()
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (col == 0) lp.marginEnd = dp(20)   // clear gap between the two columns
                row.addView(legendCell(ctx, slices[i].first, pct, sliceColors[i % sliceColors.size]), lp)
            }
            if (idxs.size == 1) {
                row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            }
            llLegend.addView(row)
        }
    }

    private fun legendCell(ctx: Context, label: String, pct: Int, color: Int): View {
        // [dot] name … %  — name fills the column, % stays right beside it
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(3).toFloat(); setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(7) }
        }
        val name = TextView(ctx).apply {
            text = label; textSize = 13f; setTextColor(Color.parseColor("#14161A"))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pctTv = TextView(ctx).apply {
            text = "$pct%"; textSize = 13f; setTextColor(Color.parseColor("#14161A"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        }
        cell.addView(dot); cell.addView(name); cell.addView(pctTv)
        return cell
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    private fun name(p: TopProductResponse): String =
        p.variant?.takeIf { it.isNotBlank() }?.let { "${p.product} ($it)" } ?: p.product

    private fun qty(q: Double): String =
        if (q == q.toLong().toDouble()) q.toLong().toString() else "%.1f".format(q)

    private fun centerLabel(): String = when (sortBy) {
        "revenue", "smart" -> "total revenue"
        "frequency"        -> "total bills"
        else               -> "total units"
    }

    private fun centerValue(total: Double): String = when (sortBy) {
        "revenue", "smart" -> CurrencyHelper.format(requireContext(), total)
        "frequency"        -> total.toLong().toString()
        else               -> qty(total)
    }

    private fun valueText(ctx: Context, p: TopProductResponse): String = when (sortBy) {
        "revenue", "smart" -> CurrencyHelper.format(ctx, p.revenue)
        "frequency"        -> "${p.frequency} bills"
        else               -> "${qty(p.quantity)} ${p.unit}"
    }

    private fun secondary(ctx: Context, p: TopProductResponse): String = when (sortBy) {
        "revenue", "smart" -> "${qty(p.quantity)} ${p.unit} · ${p.frequency} bills"
        "frequency"        -> "${qty(p.quantity)} ${p.unit} · ${CurrencyHelper.format(ctx, p.revenue)}"
        else               -> "${p.frequency} bills · ${CurrencyHelper.format(ctx, p.revenue)}"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
