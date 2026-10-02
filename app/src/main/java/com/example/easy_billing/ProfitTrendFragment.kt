package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.ProductProfitRaw
import com.example.easy_billing.network.ProfitResponse
import com.example.easy_billing.network.ProfitTrendBucket
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.CurrencyHelper
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import kotlinx.coroutines.launch

/**
 * Profit-by-product leaderboard PLUS a profit-over-time trend chart: one
 * point per hour (Today), per day (Week/Month/Custom) or per month (All) --
 * see expos-profit-trend-chart-plan.md for the full plan this implements.
 * "Pick a product" re-scopes the same trend chart to one product via
 * GET /profit/trend?product_id=...; the leaderboard below is unchanged.
 *
 * Extracted from the former ProfitChartActivity as part of the
 * single-Activity, two-Fragment refactor -- this fragment manages its own
 * "today"-first period state independently of ProfitOverviewFragment's
 * filters, same as it did when it was a separately-launched Activity (the
 * old ProfitChartActivity received FILTER/START_DATE/END_DATE/DATA extras
 * from ProfitActivity, but those were only ever used to seed the initial
 * period to match whatever the user was just looking at -- defaulting to
 * "today" here is the equivalent starting point for a tab that now opens
 * instantly rather than being launched fresh).
 */
class ProfitTrendFragment : Fragment(R.layout.fragment_profit_trend) {

    // ================= PERIOD =================
    private var filter: String = "today"
    private var startDate: String? = null
    private var endDate: String? = null

    // ================= PRODUCT LIST (for the "pick a product" dialog) =================
    private var allProducts: List<ProductProfitRaw> = emptyList()
    private var selectedProductId: Int? = null
    private var selectedProductLabel: String? = null

    // ================= VIEWS =================
    private lateinit var tvTrendCaption: TextView
    private lateinit var lineChart: LineChart
    private lateinit var tvTrendEmpty: TextView
    private lateinit var tvTrendTotal: TextView
    private lateinit var chipTrendProduct: View
    private lateinit var tvTrendProductName: TextView

    private var dataLoadedOnce = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.example.easy_billing.util.UserEventLogger.logAction("ProfitChart", "opened")

        val rv = view.findViewById<RecyclerView>(R.id.rvChart)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)

        tvTrendCaption = view.findViewById(R.id.tvTrendCaption)
        lineChart = view.findViewById(R.id.barTrendChart)
        tvTrendEmpty = view.findViewById(R.id.tvTrendEmpty)
        tvTrendTotal = view.findViewById(R.id.tvTrendTotal)
        chipTrendProduct = view.findViewById(R.id.chipTrendProduct)
        tvTrendProductName = view.findViewById(R.id.tvTrendProductName)

        setupLineChart()

        view.findViewById<android.view.View>(R.id.btnPickProduct).setOnClickListener {
            showProductPickerDialog()
        }
        view.findViewById<ImageView>(R.id.btnClearTrendProduct).setOnClickListener {
            clearSelectedProduct()
        }
        view.findViewById<ImageView>(R.id.btnChartInfo).setOnClickListener {
            showChartLegendInfoDialog()
        }

        // Leaderboard and the first chart load only need to happen once --
        // show()/hide() keep this fragment's view alive across tab
        // switches, so re-running onViewCreated's setup on every switch
        // would both be wasted work and would reset in-progress UI state
        // (selected product, chosen period) back to defaults.
        if (!dataLoadedOnce) {
            dataLoadedOnce = true

            this.rvChart = rv
            this.tvChartEmpty = tvEmpty
            loadLeaderboardProducts()
            loadTrend()
        }
    }

    private lateinit var rvChart: RecyclerView
    private lateinit var tvChartEmpty: TextView

    /** Loads the shop's full all-time product list for the leaderboard and
     *  the "Pick a product" picker -- this tab now manages its own data
     *  independently (it used to receive this list as an Intent extra from
     *  ProfitActivity when it was a separately-launched screen; as a fixed
     *  tab it fetches it the same way ProfitOverviewFragment loads its own
     *  "all" view on first open). */
    private fun loadLeaderboardProducts() {
        lifecycleScope.launch {
            try {
                val context = context ?: return@launch
                val token = context.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                    .getString("TOKEN", null) ?: return@launch

                val response: ProfitResponse = RetrofitClient.api.getProfit(token, "all", null, null)
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
                allProducts = mapped

                val ranked = mapped.sortedByDescending { it.profit }.take(10)
                if (ranked.isEmpty()) {
                    rvChart.visibility = View.GONE
                    tvChartEmpty.visibility = View.VISIBLE
                } else {
                    rvChart.layoutManager = LinearLayoutManager(requireContext())
                    rvChart.adapter = ProfitChartAdapter(ranked)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                rvChart.visibility = View.GONE
                tvChartEmpty.visibility = View.VISIBLE
            }
        }
    }

    // ================= LINE CHART SETUP (one-time styling) =================
    // Design: "Option 2" from the chart mockups -- revenue (thin, light
    // blue) and profit (bold, green) plotted together so the owner can see
    // at a glance how much of what they sold actually turned into profit.
    private fun setupLineChart() {
        lineChart.description.isEnabled = false
        lineChart.setScaleEnabled(false)
        lineChart.setPinchZoom(false)
        lineChart.setDrawGridBackground(false)
        lineChart.axisRight.isEnabled = false
        lineChart.axisLeft.isEnabled = false
        lineChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        lineChart.xAxis.setDrawGridLines(false)
        lineChart.xAxis.textColor = Color.parseColor("#9A8F79")
        lineChart.xAxis.textSize = 9.5f
        lineChart.xAxis.granularity = 1f
        lineChart.setNoDataText("")
        lineChart.setExtraOffsets(4f, 4f, 4f, 12f)

        // Legend is drawn as its own row in the XML (dots + labels, matches
        // the mockup exactly), so the chart's built-in legend stays off.
        lineChart.legend.isEnabled = false

        // Floating tooltip inside the chart itself on tap, instead of a
        // Toast -- simple dark bubble showing the period and its amount.
        lineChart.marker = TrendMarkerView(requireContext(), R.layout.marker_trend_tooltip)
    }

    /** Entry point the host Activity calls when the shared date-filter
     *  chip row changes selection — replaces the old per-fragment period
     *  pills now that the filter control lives in activity_profit.xml and
     *  drives both tabs. Safe to call before onViewCreated has finished
     *  wiring the chart; dataLoadedOnce guards that (mirrors the existing
     *  pattern in this fragment / ProfitOverviewFragment). */
    fun applyFilter(newFilter: String, newStartDate: String? = null, newEndDate: String? = null) {
        filter = newFilter
        startDate = newStartDate
        endDate = newEndDate
        if (!dataLoadedOnce) return
        loadTrend()
    }

    /** Plain-language caption shown above the chart, matching the period
     *  the bars represent -- the 60+-friendly explanation from the plan. */
    private fun captionFor(filter: String): String = when (filter) {
        "today" -> "Each point is one hour of today"
        "week" -> "Each point is one day this week"
        "month" -> "Each point is one day this month"
        "all" -> "Each point is one month since you started"
        "custom" -> "Each point is one day in your chosen range"
        else -> "Each point is one period"
    }

    // ================= TREND DATA =================
    private fun loadTrend() {
        tvTrendCaption.text = captionFor(filter)

        lifecycleScope.launch {
            try {
                val context = context ?: return@launch
                val token = context.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                    .getString("TOKEN", null) ?: return@launch

                val response = RetrofitClient.api.getProfitTrend(
                    token, filter, startDate, endDate, selectedProductId
                )

                renderTrend(response.buckets, response.total_profit)
            } catch (e: Exception) {
                // Network hiccup: leave the chart as-is rather than crash;
                // the leaderboard below still has the already-loaded data.
                tvTrendEmpty.visibility = View.VISIBLE
                tvTrendEmpty.text = "Couldn't load the chart right now"
                lineChart.visibility = View.GONE
            }
        }
    }

    private fun renderTrend(buckets: List<ProfitTrendBucket>, totalProfit: Double) {
        val currencySymbol = CurrencyHelper.getCurrencySymbol(requireContext())
        tvTrendTotal.text = "$currencySymbol${"%,.2f".format(totalProfit)}"
        tvTrendTotal.setTextColor(Color.parseColor(if (totalProfit < 0) "#A32D2D" else "#085041"))

        val allZero = buckets.all { it.profit == 0.0 }
        if (buckets.isEmpty() || allZero) {
            lineChart.visibility = View.GONE
            tvTrendEmpty.visibility = View.VISIBLE
            tvTrendEmpty.text = "Nothing sold in this period yet"
            return
        }

        lineChart.visibility = View.VISIBLE
        tvTrendEmpty.visibility = View.GONE

        val labels = buckets.map { it.label }

        // Option 1 design: a single smooth profit line with a soft teal
        // gradient fill underneath -- calmest, one thing to read, no
        // legend needed. Turns red when the period's total is a loss.
        val profitEntries = buckets.mapIndexed { index, bucket ->
            Entry(index.toFloat(), bucket.profit.toFloat())
        }
        val profitColor = if (totalProfit < 0) Color.parseColor("#D9453D") else Color.parseColor("#0F6E56")
        val profitSet = LineDataSet(profitEntries, "Profit").apply {
            color = profitColor
            lineWidth = 2.5f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.15f
            setDrawFilled(true)
            fillDrawable = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_trend_chart_fill_gradient
            )
            highLightColor = Color.parseColor("#B9C9C3")
            highlightLineWidth = 0.8f
            setDrawHorizontalHighlightIndicator(true)
        }

        lineChart.data = LineData(profitSet)
        (lineChart.marker as? TrendMarkerView)?.update(buckets, currencySymbol)
        // Hard cap on visible x-axis labels (plan §4.3): with a lot of
        // points (24 hours, 28-31 days) showing every single label is
        // unreadable on a phone, so only every Nth label is drawn; the
        // rest of the points are still there and still tappable.
        val maxLabels = 8
        val step = maxOf(1, Math.ceil(labels.size / maxLabels.toDouble()).toInt())
        lineChart.xAxis.valueFormatter = object : IndexAxisValueFormatter(labels) {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt()
                if (i < 0 || i >= labels.size) return ""
                return if (i % step == 0) labels[i] else ""
            }
        }
        lineChart.xAxis.labelCount = labels.size

        // Tap a point -> the floating marker above shows the exact value;
        // nothing extra needed here (plan §4.3, now via MarkerView instead
        // of a Toast).
        lineChart.invalidate()
    }

    // ================= PRODUCT PICKER (searchable bottom sheet) =================
    // A plain AlertDialog list stopped scaling once a shop had more than a
    // screenful of products -- no way to search, just a long scroll. This
    // reuses the same searchable bottom-sheet pattern as the credit-account
    // picker (dialog_customer_picker.xml / InvoiceActivity.handleCreditFlow)
    // so it reads as the same app and handles a long catalog comfortably.
    // Simple "i" explainer for the leaderboard card's green/red legend --
    // same idea as ProfitOverviewFragment.showProfitInfoDialog(), just
    // explaining what the bar colors and lengths mean instead of opening
    // a plain paragraph of text at the bottom of the page.
    private fun showChartLegendInfoDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_chart_legend_info, null)
        val btnGotIt = view.findViewById<Button>(R.id.btnGotIt)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnGotIt.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showProductPickerDialog() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_product_picker, null)

        val etSearch = view.findViewById<android.widget.EditText>(R.id.etSearchProduct)
        val rvProducts = view.findViewById<RecyclerView>(R.id.rvProductPicker)
        val tvCount = view.findViewById<TextView>(R.id.tvProductPickerCount)

        rvProducts.layoutManager = LinearLayoutManager(requireContext())
        tvCount.text = if (allProducts.size == 1) "1 product" else "${allProducts.size} products"

        lateinit var adapter: ProductPickerAdapter
        adapter = ProductPickerAdapter(allProducts, selectedProductId) { product ->
            dialog.dismiss()
            if (product == null) {
                clearSelectedProduct()
                return@ProductPickerAdapter
            }
            val label = if (product.variant.isNullOrBlank()) product.productName
                else "${product.productName} (${product.variant})"
            selectedProductId = product.productId
            selectedProductLabel = label
            if (selectedProductId == null) {
                // No product_id on this row (older cached data) -- can't
                // drill down to it; let the user know plainly instead of
                // silently showing the whole-shop chart.
                android.widget.Toast.makeText(
                    requireContext(),
                    "That product can't be charted on its own yet — pull to refresh and try again.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@ProductPickerAdapter
            }
            chipTrendProduct.visibility = View.VISIBLE
            tvTrendProductName.text = selectedProductLabel
            loadTrend()
        }
        rvProducts.adapter = adapter

        // Debounced search Handler, scoped to this dialog's own lifetime
        // (not viewLifecycleOwner) -- the handler and its callbacks are
        // local to the dialog's view hierarchy and already stop mattering
        // the moment the dialog is dismissed, same as the original
        // Activity-hosted version, so there's nothing left running once
        // the BottomSheetDialog is gone.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var runnable: Runnable? = null
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                runnable?.let { handler.removeCallbacks(it) }
                runnable = Runnable {
                    val query = s?.toString()?.trim()?.take(50) ?: ""
                    adapter.filter(query)
                }
                handler.postDelayed(runnable!!, 250)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        dialog.setOnDismissListener {
            runnable?.let { handler.removeCallbacks(it) }
        }

        dialog.setContentView(view)
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.show()
    }

    private fun clearSelectedProduct() {
        selectedProductId = null
        selectedProductLabel = null
        chipTrendProduct.visibility = View.GONE
        loadTrend()
    }
}

/**
 * Floating in-chart tooltip for the profit trend line -- replaces the old
 * Toast-on-tap with a small dark bubble that appears right above the
 * tapped point, showing just the period's label and its profit amount.
 * [update] is called once per [ProfitTrendFragment.renderTrend] so the
 * marker always reads the bucket list currently on screen.
 */
private class TrendMarkerView(
    context: android.content.Context,
    layoutResource: Int
) : MarkerView(context, layoutResource) {

    private val tvLabel: TextView = findViewById(R.id.tvMarkerLabel)
    private val tvAmount: TextView = findViewById(R.id.tvMarkerAmount)
    private var buckets: List<ProfitTrendBucket> = emptyList()
    private var currencySymbol: String = ""
    private var lastIndex: Int = -1

    fun update(buckets: List<ProfitTrendBucket>, currencySymbol: String) {
        this.buckets = buckets
        this.currencySymbol = currencySymbol
    }

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        val index = e?.x?.toInt() ?: -1
        lastIndex = index
        if (index in buckets.indices) {
            val bucket = buckets[index]
            tvLabel.text = bucket.label
            tvAmount.text = "$currencySymbol${"%,.2f".format(bucket.profit)}"
        }
        super.refreshContent(e, highlight)
    }

    // Centers the bubble above the tapped point by default, but the last
    // two points on the right edge have no room for that -- a centered
    // bubble there gets clipped by the screen edge, so for those it opens
    // fully to the left of the point instead.
    override fun getOffset(): MPPointF {
        val nearRightEdge = buckets.isNotEmpty() && lastIndex >= buckets.size - 2
        val offsetX = if (nearRightEdge) -width.toFloat() - 6f else -(width / 2f)
        return MPPointF(offsetX, -height.toFloat() - 10f)
    }
}

/**
 * Searchable list adapter backing the "Pick a product" bottom sheet
 * (dialog_product_picker.xml). "All products" is always a virtual first
 * row (represented as a null item) so it's always one tap away, even
 * while a search query is active and has filtered the real rows out.
 */
private class ProductPickerAdapter(
    private val allItems: List<ProductProfitRaw>,
    private var selectedProductId: Int?,
    private val onClick: (ProductProfitRaw?) -> Unit
) : RecyclerView.Adapter<ProductPickerAdapter.VH>() {

    private var shown: List<ProductProfitRaw> = allItems

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvProductPickerName)
        val variant: TextView = view.findViewById(R.id.tvProductPickerVariant)
        val check: ImageView = view.findViewById(R.id.ivProductPickerCheck)
        val divider: View = view.findViewById(R.id.viewProductPickerDivider)
    }

    fun filter(query: String) {
        shown = if (query.isBlank()) allItems else allItems.filter {
            it.productName.contains(query, ignoreCase = true) ||
                (it.variant?.contains(query, ignoreCase = true) == true)
        }
        notifyDataSetChanged()
    }

    // Row 0 is always the virtual "All products" row; real products start
    // at index 1 so clearing the search never hides the way back out.
    override fun getItemCount(): Int = shown.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product_picker_row, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position == 0) {
            holder.name.text = "All products"
            holder.variant.visibility = View.GONE
            holder.check.visibility = if (selectedProductId == null) View.VISIBLE else View.GONE
            holder.divider.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { onClick(null) }
            return
        }

        val item = shown[position - 1]
        val name = item.productName.trim()
        holder.name.text = name
        if (item.variant.isNullOrBlank()) {
            holder.variant.visibility = View.GONE
        } else {
            holder.variant.visibility = View.VISIBLE
            holder.variant.text = item.variant
        }
        holder.check.visibility =
            if (item.productId != null && item.productId == selectedProductId) View.VISIBLE else View.GONE
        holder.divider.visibility = if (position == shown.size) View.INVISIBLE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
