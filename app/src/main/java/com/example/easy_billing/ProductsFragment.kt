package com.example.easy_billing.fragments

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.Filterable
import com.example.easy_billing.R
import com.example.easy_billing.ReportFilter
import com.example.easy_billing.adapter.RankedRowAdapter
import com.example.easy_billing.adapter.SalesRow
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.TopProductResponse
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.CurrencyHelper
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.roundToInt

/**
 * Best Sellers — a KPI card (best and slowest product) and one ranked
 * breakdown card. The sort selector (Fast moving / Revenue / Popular / Smart)
 * decides how the products are ranked and which figure each row shows.
 */
class ProductsFragment : Fragment(R.layout.fragment_products), Filterable {

    private lateinit var tvSortChip: TextView
    private lateinit var rowSortChip: View
    private lateinit var tvBreakdownCaption: TextView
    private lateinit var tvBestName: TextView
    private lateinit var tvBestValue: TextView
    private lateinit var tvBestSub: TextView
    private lateinit var tvWorstName: TextView
    private lateinit var tvWorstValue: TextView
    private lateinit var tvWorstSub: TextView
    private lateinit var rvProducts: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvNotSold: TextView
    private var loadJob: kotlinx.coroutines.Job? = null
    private lateinit var adapter: RankedRowAdapter
    private lateinit var tvBreakdownMore: View
    private lateinit var tvBreakdownMoreText: TextView
    private lateinit var ivBreakdownMoreChevron: View

    private var currentFilter = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private var sortBy = "quantity"   // quantity | revenue | frequency | smart

    private val sdf = AppTime.isoDate()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.example.easy_billing.util.UserEventLogger.logAction("ProductsFragment", "opened")

        tvSortChip  = view.findViewById(R.id.tvSortChip)
        rowSortChip = view.findViewById(R.id.rowSortChip)
        tvBreakdownCaption = view.findViewById(R.id.tvBreakdownCaption)
        tvBestName  = view.findViewById(R.id.tvBestName)
        tvBestValue = view.findViewById(R.id.tvBestValue)
        tvBestSub   = view.findViewById(R.id.tvBestSub)
        tvWorstName  = view.findViewById(R.id.tvWorstName)
        tvWorstValue = view.findViewById(R.id.tvWorstValue)
        tvWorstSub   = view.findViewById(R.id.tvWorstSub)
        rvProducts  = view.findViewById(R.id.rvProducts)
        tvEmpty     = view.findViewById(R.id.tvEmpty)
        tvNotSold   = view.findViewById(R.id.tvNotSold)

        rvProducts.layoutManager = LinearLayoutManager(requireContext())
        rvProducts.isNestedScrollingEnabled = false
        adapter = RankedRowAdapter()
        rvProducts.adapter = adapter
        tvBreakdownMore = view.findViewById(R.id.tvBreakdownMore)
        tvBreakdownMoreText = view.findViewById(R.id.tvBreakdownMoreText)
        ivBreakdownMoreChevron = view.findViewById(R.id.ivBreakdownMoreChevron)
        tvBreakdownMore.setOnClickListener { adapter.toggleExpanded(); updateMoreButton() }

        tvSortChip.text = sortLabel()
        rowSortChip.setOnClickListener { showSortMenu(it) }

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

    private fun sortLabel(key: String = sortBy): String = getString(when (key) {
        "revenue"   -> R.string.products_sort_revenue
        "frequency" -> R.string.products_sort_popular
        "smart"     -> R.string.products_sort_smart
        else        -> R.string.products_sort_fast
    })

    private fun showSortMenu(anchor: View) {
        val keys = listOf("quantity", "revenue", "frequency", "smart")
        val selected = keys.indexOf(sortBy).coerceAtLeast(0)
        com.example.easy_billing.ui.ThemedDropdown.show(
            anchor, keys.map { sortLabel(it) }, selected, rightAlign = true
        ) { idx ->
            sortBy = keys[idx]
            tvSortChip.text = sortLabel()
            loadProducts()
        }
    }

    // ── Data loading ───────────────────────────────────────────────────────────

    private fun loadProducts() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
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
                showNotSold(products)

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                view?.let { v ->
                    com.google.android.material.snackbar.Snackbar
                        .make(v, R.string.reports_toast_load_failed, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.reports_retry) { loadProducts() }
                        .show()
                }
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
            adapter.updateData(emptyList()); updateMoreButton()
            bindKpi(tvBestName, tvBestValue, tvBestSub, null)
            bindKpi(tvWorstName, tvWorstValue, tvWorstSub, null)
            return
        }
        tvEmpty.visibility = View.GONE
        rvProducts.visibility = View.VISIBLE

        // Smart = the server's weighted ranking; keep its order (don't re-sort by money).
        val ranked = if (sortBy == "smart") products else products.sortedByDescending { metricOf(it) }
        val best = ranked.first()
        // With a single product there is no "slowest" to compare against.
        val worst = if (ranked.size >= 2) ranked.last() else null

        bindKpi(tvBestName, tvBestValue, tvBestSub, best)
        if (worst != null) bindKpi(tvWorstName, tvWorstValue, tvWorstSub, worst)
        else { bindKpi(tvWorstName, tvWorstValue, tvWorstSub, null); tvWorstSub.text = getString(R.string.products_only_one) }

        tvBreakdownCaption.text = getString(R.string.products_card_caption)

        // Breakdown list — best first (medals, bar relative to the #1 seller).
        adapter.updateData(ranked.map { p ->
            SalesRow(
                label = name(p), revenue = metricOf(p), bills = p.frequency,
                subText = secondary(ctx, p), valueText = valueText(ctx, p)
            )
        })
        updateMoreButton()
    }

    private fun updateMoreButton() {
        if (!adapter.canExpand) { tvBreakdownMore.visibility = View.GONE; return }
        tvBreakdownMore.visibility = View.VISIBLE
        ivBreakdownMoreChevron.rotation = if (adapter.expanded) 180f else 0f
        tvBreakdownMoreText.text = if (adapter.expanded) getString(R.string.reports_show_less)
            else getString(R.string.products_see_all, adapter.totalCount)
    }

    private fun bindKpi(nameTv: TextView, valueTv: TextView, subTv: TextView, p: TopProductResponse?) {
        if (p == null) {
            nameTv.text = getString(R.string.dash); valueTv.text = getString(R.string.dash); subTv.text = getString(R.string.dash)
            return
        }
        nameTv.text = name(p)
        valueTv.text = valueText(requireContext(), p)
        subTv.text = secondary(requireContext(), p)
    }

    /** Active products that had no sales at all in the chosen period. */
    private suspend fun showNotSold(sold: List<TopProductResponse>) {
        val ctx = context ?: return
        val soldNames = sold.map { name(it).trim().lowercase() }.toSet()
        val names = try {
            com.example.easy_billing.db.AppDatabase.getDatabase(ctx).productDao().getAll()
                .map { p -> p.variant?.takeIf { it.isNotBlank() }?.let { "${p.name} ($it)" } ?: p.name }
                .filter { it.trim().lowercase() !in soldNames }
                .distinct()
        } catch (e: Exception) { emptyList() }
        if (names.isEmpty()) { tvNotSold.visibility = View.GONE; return }
        val shown = names.take(8).joinToString(", ")
        val more = names.size - 8
        tvNotSold.text = getString(R.string.products_not_sold, shown) +
            if (more > 0) " " + getString(R.string.products_not_sold_more, more) else ""
        tvNotSold.visibility = View.VISIBLE
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    private fun name(p: TopProductResponse): String =
        p.variant?.takeIf { it.isNotBlank() }?.let { "${p.product} ($it)" } ?: p.product

    private fun qty(q: Double): String =
        if (q == q.toLong().toDouble()) q.toLong().toString() else "%.1f".format(q)

    private fun valueText(ctx: Context, p: TopProductResponse): String = when (sortBy) {
        "revenue", "smart" -> CurrencyHelper.format(ctx, p.revenue)
        "frequency"        -> ctx.getString(R.string.reports_bills_count, p.frequency)
        else               -> "${qty(p.quantity)} ${p.unit}"
    }

    private fun secondary(ctx: Context, p: TopProductResponse): String = when (sortBy) {
        "revenue", "smart" -> "${qty(p.quantity)} ${p.unit} · " + ctx.getString(R.string.reports_bills_count, p.frequency)
        "frequency"        -> "${qty(p.quantity)} ${p.unit} · ${CurrencyHelper.format(ctx, p.revenue)}"
        else               -> ctx.getString(R.string.reports_bills_count, p.frequency) + " · " + CurrencyHelper.format(ctx, p.revenue)
    }
}
