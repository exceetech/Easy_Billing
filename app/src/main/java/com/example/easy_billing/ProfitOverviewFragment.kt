package com.example.easy_billing

import com.example.easy_billing.R

import android.app.Dialog
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.CurrencyHelper
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.ProductProfitRaw
import com.example.easy_billing.network.ProfitResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.InvoicePdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Owns the "Profit Analytics" / Overview tab content: period filter chips,
 * the tabbed profit-calculation card, search, and the by-product list with
 * print. Extracted from the former ProfitActivity as part of the
 * single-Activity, two-Fragment refactor -- see ProfitActivity (host) for
 * the shared toolbar / tab-switch row this fragment no longer owns.
 */
class ProfitOverviewFragment : Fragment(R.layout.fragment_profit_overview) {

    // ================= NEW UI =================
    private lateinit var recyclerView: RecyclerView
    private lateinit var cardProfitProducts: android.view.View
    private lateinit var layoutProfitEmpty: android.view.View
    private lateinit var tvProfitEmptyTitle: TextView
    private lateinit var tvProfitEmptyTitleAccent: TextView
    private lateinit var tvProfitEmptyBody: TextView
    private lateinit var profitAdapter: ProfitAdapter
    private lateinit var etSearch: EditText

    private var fullList: List<ProductProfitRaw> = emptyList()
    private var currentSearchQuery: String = ""

    // ================= EXISTING =================
    private var latestProfitList: List<ProductProfitRaw> = emptyList()
    private var currentFilter = "all"

    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private var dataLoadedOnce = false

    // ================= ON VIEW CREATED =================

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.example.easy_billing.util.UserEventLogger.logAction("Profit", "opened")

        setupRecycler(view)
        setupSearch()
        setupProfitTabs(view)

        view.findViewById<Button>(R.id.btnPrint).setOnClickListener {
            showPrintConfirmDialog()
        }

        if (!dataLoadedOnce) {
            dataLoadedOnce = true
            loadProfit("all")
        }
    }

    // Offline-session-timeout coverage (see SessionTimeoutGuard for why this
    // isn't done via extending BaseActivity instead).
    override fun onResume() {
        super.onResume()
        com.example.easy_billing.util.SessionTimeoutGuard.start(requireActivity() as androidx.appcompat.app.AppCompatActivity)
    }

    override fun onPause() {
        super.onPause()
        com.example.easy_billing.util.SessionTimeoutGuard.stop(requireActivity())
    }

    // ================= PROFIT CALCULATION TABS =================
    // Two tabs sharing one card: "Today's sales" (default) and "Total
    // earned vs spent". Pure UI toggle — the underlying figures were
    // already computed once in loadProfit() and mirrored into both tabs'
    // views, so switching tabs never re-fetches or re-calculates anything.
    private fun setupProfitTabs(root: View) {
        val tabSales = root.findViewById<TextView>(R.id.tabProfitSales)
        val tabTotal = root.findViewById<TextView>(R.id.tabProfitTotal)
        val panelSales = root.findViewById<android.view.View>(R.id.panelProfitSales)
        val panelTotal = root.findViewById<android.view.View>(R.id.panelProfitTotal)

        root.findViewById<ImageView>(R.id.btnProfitInfo).setOnClickListener {
            showProfitInfoDialog()
        }

        fun showSales() {
            panelSales.visibility = android.view.View.VISIBLE
            panelTotal.visibility = android.view.View.GONE
            tabSales.setBackgroundResource(R.drawable.bg_login_tab_active)
            tabSales.setTextColor(Color.parseColor("#FFFFFF"))
            tabTotal.setBackgroundResource(android.R.color.transparent)
            tabTotal.setTextColor(Color.parseColor("#8C8576"))
        }

        fun showTotal() {
            panelTotal.visibility = android.view.View.VISIBLE
            panelSales.visibility = android.view.View.GONE
            tabTotal.setBackgroundResource(R.drawable.bg_login_tab_active)
            tabTotal.setTextColor(Color.parseColor("#FFFFFF"))
            tabSales.setBackgroundResource(android.R.color.transparent)
            tabSales.setTextColor(Color.parseColor("#8C8576"))
        }

        tabSales.setOnClickListener { showSales() }
        tabTotal.setOnClickListener { showTotal() }
    }

    // ================= RECYCLER =================

    private fun setupRecycler(root: View) {
        recyclerView = root.findViewById(R.id.rvProducts)
        cardProfitProducts = root.findViewById(R.id.cardProfitProducts)
        layoutProfitEmpty = root.findViewById(R.id.layoutProfitEmpty)
        tvProfitEmptyTitle = root.findViewById(R.id.tvProfitEmptyTitle)
        tvProfitEmptyTitleAccent = root.findViewById(R.id.tvProfitEmptyTitleAccent)
        tvProfitEmptyBody = root.findViewById(R.id.tvProfitEmptyBody)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // No ItemDecoration here — item_profit_simple.xml already draws its
        // own hairline divider per row (viewRowDivider), same as Inventory's
        // rows. An extra DividerItemDecoration on top of that was adding a
        // redundant gap between cards.

        // Rows expand in place now (see ProfitAdapter) instead of
        // opening a popup, so the adapter no longer takes a click callback.
        profitAdapter = ProfitAdapter()

        recyclerView.adapter = profitAdapter

        // Applied to the shared card container rather than the recycler
        // directly, since the rounded background lives on
        // cardProfitProducts now.
        cardProfitProducts.clipToOutline = true

        etSearch = root.findViewById(R.id.etSearch)
    }

    /** Shows/hides the recycler vs. the empty state, and picks the right copy
     *  for "nothing sold in this period" vs. "search found nothing". Called
     *  after every list update — load, filter change, and search. */
    private fun updateProfitListState(list: List<ProductProfitRaw>) {
        layoutProfitEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        recyclerView.visibility = if (list.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        if (currentSearchQuery.isNotEmpty()) {
            tvProfitEmptyTitle.text = "No matches"
            tvProfitEmptyTitleAccent.text = "found"
            tvProfitEmptyBody.text = "No product matches your search. Try a different name or variant."
        } else {
            tvProfitEmptyTitle.text = "No products"
            tvProfitEmptyTitleAccent.text = "yet"
            tvProfitEmptyBody.text = "Sell something in this period and its revenue, cost and profit will break down here by product."
        }
    }

    // ================= SEARCH =================

    private fun setupSearch() {

        etSearch.addTextChangedListener(object : android.text.TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                val query = s.toString().lowercase()
                currentSearchQuery = query

                val filtered = fullList.filter {
                    it.productName.lowercase().contains(query) ||
                            (it.variant?.lowercase()?.contains(query) ?: false)
                }

                profitAdapter.submitList(filtered)
                updateProfitListState(filtered)
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    // ================= FILTER (shared control lives in ProfitActivity) =================

    /** Entry point the host Activity calls when the shared date-filter
     *  chip row changes selection — replaces the old per-fragment chip
     *  click listeners now that the chips themselves moved to
     *  activity_profit.xml. */
    fun applyFilter(newFilter: String, newStartDate: String? = null, newEndDate: String? = null) {
        currentFilter = newFilter
        customStartDate = newStartDate
        customEndDate = newEndDate
        loadProfit(newFilter, newStartDate, newEndDate)
    }

    // ================= API =================

    private fun loadProfit(
        filter: String,
        start: String? = null,
        end: String? = null
    ) {

        lifecycleScope.launch {

            try {

                val context = context ?: return@launch
                val token = context.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                    .getString("TOKEN", null)

                if (!token.isNullOrEmpty()) {
                    val response: ProfitResponse =
                        RetrofitClient.api.getProfit(
                            token,
                            filter,
                            start,
                            end
                        )

                    val summary = response.summary
                    val localPurchaseExpense = withContext(Dispatchers.IO) {
                        fetchLocalPurchaseExpense()
                    }
                    val finalExpense = maxOf(summary.expense, localPurchaseExpense)

                    val root = view ?: return@launch
                    val currencySymbol = CurrencyHelper.getCurrencySymbol(requireContext())
                    root.findViewById<TextView>(R.id.tvRevenue).text = "$currencySymbol${"%.2f".format(summary.revenue)}"
                    root.findViewById<TextView>(R.id.tvCost).text = "$currencySymbol${"%.2f".format(summary.cost)}"
                    root.findViewById<TextView>(R.id.tvExpense).text = "$currencySymbol${"%.2f".format(finalExpense)}"

                    // Moving-average redesign, Phase 5: the "Loss" tile now
                    // includes purchase-return gain/loss alongside scrap
                    // loss — both are the same thing economically: shelf
                    // value that didn't come back as revenue. This
                    // keeps the tile consistent with `summary.profit`,
                    // which already nets both out. A negative
                    // purchaseReturnVariance (net gain on returns) reduces
                    // this figure, same as it reduces the headline loss.
                    val combinedLoss = summary.loss + summary.purchaseReturnVariance
                    root.findViewById<TextView>(R.id.tvLoss).text = "$currencySymbol${"%.2f".format(combinedLoss)}"

                    // "Total profit" headline for the "Total earned vs
                    // spent" tab -- per explicit request, this subtracts
                    // Expense too (Total earned - Total cost - Expense -
                    // Loss), unlike the app's real accounting profit
                    // (summary.profit, which never subtracts Expense -- see
                    // pos-backend profit_routes.py). This is therefore a
                    // DIFFERENT, bigger-picture cash figure than
                    // "Profit from sales" in the other tab, which still
                    // shows the true summary.profit value.
                    val totalProfit = summary.revenue - summary.cost - finalExpense - combinedLoss
                    val netTv = root.findViewById<TextView>(R.id.tvNetProfit)
                    netTv.text = "$currencySymbol${"%.2f".format(totalProfit)}"
                    netTv.setTextColor(
                        Color.parseColor(if (totalProfit < 0) "#A32D2D" else "#1A1A18")
                    )

                    // "Today's sales" tab mirrors the same already-computed
                    // figures (Revenue, Total cost, Loss, and the same
                    // Net profit value as "Profit from sales") into a
                    // second set of views — no new calculation, just a
                    // second display so the tab switch is instant.
                    root.findViewById<TextView>(R.id.tvEarned).text = "$currencySymbol${"%.2f".format(summary.revenue)}"
                    root.findViewById<TextView>(R.id.tvCostSales).text = "$currencySymbol${"%.2f".format(summary.cost)}"
                    root.findViewById<TextView>(R.id.tvLossSales).text = "$currencySymbol${"%.2f".format(combinedLoss)}"
                    val profitFromSalesTv = root.findViewById<TextView>(R.id.tvProfitFromSales)
                    profitFromSalesTv.text = "$currencySymbol${"%.2f".format(summary.profit)}"
                    profitFromSalesTv.setTextColor(
                        Color.parseColor(if (summary.profit < 0) "#A32D2D" else "#16A34A")
                    )
                    val layoutMarginPill = root.findViewById<android.view.View>(R.id.layoutMarginPill)
                    if (summary.growth != null) {
                        layoutMarginPill.visibility = android.view.View.VISIBLE
                        val growthPct = summary.growth.profit_percentage
                        val positive = growthPct >= 0
                        val pillFg = if (positive) "#085041" else "#791F1F"
                        val sign = if (positive) "+" else ""

                        root.findViewById<TextView>(R.id.tvMargin).apply {
                            text = "${sign}${Math.round(growthPct)}% growth"
                            setTextColor(Color.parseColor(pillFg))
                        }
                        layoutMarginPill.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(
                                Color.parseColor(if (positive) "#DDEEEE" else "#FBEDED")
                            )
                        root.findViewById<ImageView>(R.id.ivMarginTrend).apply {
                            setColorFilter(Color.parseColor(pillFg))
                            setImageResource(
                                if (positive) R.drawable.ic_si_trend_up
                                else R.drawable.ic_si_trend_down
                            )
                        }
                    } else {
                        layoutMarginPill.visibility = android.view.View.GONE
                    }

                    val mapped = response.products.map {

                        ProductProfitRaw(
                            productId = it.product_id,
                            productName = it.product_name,
                            variant = it.variant,
                            unit = it.unit,
                            totalQty = it.qty,
                            revenue = it.revenue,
                            cost = it.cost,
                            profit = it.profit,
                            added = it.added,
                            sold = it.sold,
                            remaining = it.remaining,
                            lossQty = it.lossQty,
                            lossAmount = it.lossAmount
                        )
                    }

                    latestProfitList = mapped
                    fullList = mapped
                    currentSearchQuery = ""
                    profitAdapter.submitList(mapped)
                    updateProfitListState(mapped)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runCatching {
                    val localPurchaseExpense = withContext(Dispatchers.IO) {
                        fetchLocalPurchaseExpense()
                    }
                    val root = view ?: return@runCatching
                    val currencySymbol = CurrencyHelper.getCurrencySymbol(requireContext())
                    root.findViewById<TextView>(R.id.tvExpense).text = "$currencySymbol${"%.2f".format(localPurchaseExpense)}"
                }
            }
        }
    }

    private suspend fun fetchLocalPurchaseExpense(): Double {
        val db = AppDatabase.getDatabase(requireContext())
        val cal = AppTime.calendar()

        val todayStart = cal.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val todayEnd = cal.apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        return when (currentFilter) {
            "today" -> db.purchaseDao().getTotalExpenseBetween(todayStart, todayEnd)
            "week" -> {
                val startCal = AppTime.calendar().apply {
                    set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                db.purchaseDao().getTotalExpenseBetween(startCal.timeInMillis, todayEnd)
            }
            "month" -> {
                val startCal = AppTime.calendar().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                db.purchaseDao().getTotalExpenseBetween(startCal.timeInMillis, todayEnd)
            }
            "year" -> {
                val startCal = AppTime.calendar().apply {
                    set(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                db.purchaseDao().getTotalExpenseBetween(startCal.timeInMillis, todayEnd)
            }
            "custom" -> {
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val startMs = customStartDate?.let { runCatching { format.parse(it)?.time }.getOrNull() } ?: 0L
                val endMs = customEndDate?.let { runCatching { (format.parse(it)?.time ?: 0L) + 86399999L }.getOrNull() } ?: System.currentTimeMillis()
                db.purchaseDao().getTotalExpenseBetween(startMs, endMs)
            }
            else -> db.purchaseDao().getTotalExpenseAll()
        }
    }


    // ================= PRINT (UNCHANGED) =================
    private fun printProfitReport() {

        if (latestProfitList.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.profitactivity_no_data_to_print), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {

            var totalRevenue = 0.0
            var totalCost = 0.0
            var totalProfit = 0.0
            var totalLoss = 0.0
            var totalExpense: Double

            val rows = mutableListOf<List<String>>()
            val printCurrencySymbol = CurrencyHelper.getCurrencySymbol(requireContext())
            val productDao = AppDatabase.getDatabase(requireContext()).productDao()

            latestProfitList.forEach { item ->

                val netProfit = item.profit - item.lossAmount

                totalRevenue += item.revenue
                totalCost += item.cost
                totalProfit += item.profit
                totalLoss += item.lossAmount

                // Print-only display name: "Item name (variant) . Brand . Category",
                // dropping any segment that's missing -- brand/category are looked
                // up from the live product row (by id) since the sold-item snapshot
                // in bill_items only keeps name/variant, not brand/category. This
                // never touches the profit numbers, just how the name reads on paper.
                val product = item.productId?.let { id -> productDao.getById(id) }
                val itemSegment = if (!item.variant.isNullOrBlank()) {
                    "${item.productName} (${item.variant})"
                } else {
                    item.productName
                }
                val displayName = listOfNotNull(
                    itemSegment,
                    product?.brand?.takeIf { it.isNotBlank() },
                    product?.category?.takeIf { it.isNotBlank() }
                ).joinToString(" . ")

                rows.add(
                    listOf(
                        displayName, "${item.totalQty}", "${item.unit}",
                        "$printCurrencySymbol%.2f".format(item.revenue),
                        "$printCurrencySymbol%.2f".format(item.cost),
                        "$printCurrencySymbol%.2f".format(item.profit),
                        "Added:${item.added.toInt()} | Sold:${item.sold.toInt()} | Loss:${item.lossQty.toInt()}",
                        "${item.remaining.toInt()}",
                        "$printCurrencySymbol-%.2f".format(item.lossAmount),
                        "$printCurrencySymbol%.2f".format(netProfit),
                        getInsight(item, netProfit)
                    )
                )
            }

            // 🔥 Expense from UI (already loaded)
            val expense = (view?.findViewById<TextView>(R.id.tvExpense)
                ?.text?.toString()?.replace(printCurrencySymbol, "")?.toDoubleOrNull()) ?: 0.0

            totalExpense = expense

            withContext(Dispatchers.Main) {

                val activity = activity ?: return@withContext
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                try {
                    val (startDate, endDate) = getFilterDateRange()

                    InvoicePdfGenerator.generateProfitPdf(
                        activity = activity,
                        rows = rows,
                        totalProfit = totalProfit,
                        totalRevenue = totalRevenue,
                        totalCost = totalCost,
                        totalExpense = totalExpense,
                        totalLoss = totalLoss,
                        startDate = startDate,
                        endDate = endDate
                    )

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(activity, getString(R.string.profitactivity_print_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Simple "i" explainer for the Overview page's first card -- plain-
    // language difference between the two tabs (Sales profit / Business
    // profit), not a settings or calculation change, just a one-screen
    // explanation reusing the app's champagne dialog look.
    private fun showProfitInfoDialog() {

        val view = layoutInflater.inflate(R.layout.dialog_profit_info, null)
        val btnGotIt = view.findViewById<Button>(R.id.btnGotIt)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnGotIt.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showPrintConfirmDialog() {

        val view = layoutInflater.inflate(R.layout.dialog_confirm_print, null)

        val tvInfo = view.findViewById<TextView>(R.id.tvInfo)
        val btnPrint = view.findViewById<Button>(R.id.btnPrint)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)

        val filterText = when (currentFilter) {
            "today" -> "Today"
            "week" -> "This Week"
            "month" -> "This Month"
            "custom" -> "Custom (${customStartDate ?: ""} → ${customEndDate ?: ""})"
            else -> "All Time"
        }

        tvInfo.text = filterText

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnPrint.setOnClickListener {
            dialog.dismiss()
            printProfitReport()
        }

        dialog.show()
    }

    private fun getInsight(item: ProductProfitRaw, netProfit: Double): String {
        return when {
            netProfit < 0 -> "Loss product"
            item.lossQty > item.sold -> "High wastage"
            item.remaining > item.sold -> "Dead stock"
            else -> "Good product"
        }
    }

    private fun getFilterDateRange(): Pair<String, String> {

        // Corrected internet clock in the shop timezone (matches backend reports).
        val cal = AppTime.calendar()
        val format = AppTime.isoDate()

        val today = format.format(cal.time)

        return when (currentFilter) {

            "today" -> {
                Pair(today, today)
            }

            "week" -> {
                val startCal = AppTime.calendar()
                startCal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                val start = format.format(startCal.time)

                val endCal = AppTime.calendar()
                endCal.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
                val endRaw = format.format(endCal.time)

                val end = minOf(endRaw, today)   // 🔥 CAP HERE

                Pair(start, end)
            }

            "month" -> {
                val startCal = AppTime.calendar()
                startCal.set(Calendar.DAY_OF_MONTH, 1)
                val start = format.format(startCal.time)

                val endCal = AppTime.calendar()
                endCal.set(
                    Calendar.DAY_OF_MONTH,
                    endCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                )
                val endRaw = format.format(endCal.time)

                val end = minOf(endRaw, today)   // 🔥 CAP HERE

                Pair(start, end)
            }

            "year" -> {
                val startCal = AppTime.calendar()
                startCal.set(Calendar.DAY_OF_YEAR, 1)
                val start = format.format(startCal.time)

                val endCal = AppTime.calendar()
                endCal.set(
                    Calendar.DAY_OF_YEAR,
                    endCal.getActualMaximum(Calendar.DAY_OF_YEAR)
                )
                val endRaw = format.format(endCal.time)

                val end = minOf(endRaw, today)   // 🔥 CAP HERE

                Pair(start, end)
            }

            "custom" -> {

                val start = customStartDate ?: ""
                val endRaw = customEndDate ?: ""

                val end = if (endRaw.isNotEmpty())
                    minOf(endRaw, today)
                else
                    ""

                Pair(start, end)
            }

            else -> {
                Pair("All Time", "")
            }
        }
    }
}
