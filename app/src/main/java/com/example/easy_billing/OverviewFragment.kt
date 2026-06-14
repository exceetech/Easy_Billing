package com.example.easy_billing.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.Filterable
import com.example.easy_billing.R
import com.example.easy_billing.ReportFilter
import com.example.easy_billing.network.OverviewResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.CurrencyHelper
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class OverviewFragment : Fragment(R.layout.fragment_overview), Filterable {

    // ── Hero card ───────────────────────────────────────────────────────────
    private lateinit var tvRevenue: TextView
    private lateinit var tvRevenueGrowth: TextView
    private lateinit var tvRevenuePrev: TextView
    private lateinit var chartRevenueMini: LineChart

    // ── Header ──────────────────────────────────────────────────────────────
    private lateinit var tvGreeting: TextView
    private lateinit var tvShopName: TextView
    private lateinit var tvInitials: TextView

    // ── Metric grid ─────────────────────────────────────────────────────────
    private lateinit var tvBills: TextView
    private lateinit var tvBillsGrowth: TextView
    private lateinit var tvAverage: TextView
    private lateinit var tvAverageGrowth: TextView
    private lateinit var tvReturns: TextView
    private lateinit var tvReturnsLabel: TextView
    private lateinit var tvCancelled: TextView
    private lateinit var tvCancelledAmount: TextView

    // ── Payment split text ───────────────────────────────────────────────────
    private lateinit var tvCashAmount: TextView
    private lateinit var tvCashPct: TextView
    private lateinit var tvUpiAmount: TextView
    private lateinit var tvUpiPct: TextView
    private lateinit var tvCardAmount: TextView
    private lateinit var tvCardPct: TextView
    private lateinit var tvCreditAmount: TextView
    private lateinit var tvCreditPct: TextView

    // ── Payment progress bars (fill + spacer pairs) ──────────────────────────
    private lateinit var vProgCashFill: View
    private lateinit var vProgCashSpacer: View
    private lateinit var vProgUpiFill: View
    private lateinit var vProgUpiSpacer: View
    private lateinit var vProgCardFill: View
    private lateinit var vProgCardSpacer: View
    private lateinit var vProgCreditFill: View
    private lateinit var vProgCreditSpacer: View

    // ── Header ───────────────────────────────────────────────────────────────
    private lateinit var tvDate: TextView

    private var currentFilter = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String? = null

    // 🔥 H5 FIX: Locale.US guarantees ASCII digits for API dates
    private val sdf = AppTime.isoDate()   // app timezone (matches backend)

    // Semi-transparent white sparkline on the navy hero card
    private val sparklineColor = Color.argb(180, 255, 255, 255)

    // Convert dp → pixels for programmatic padding/margins
    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Hero
        tvRevenue       = view.findViewById(R.id.tvRevenue)
        tvRevenueGrowth = view.findViewById(R.id.tvRevenueGrowth)
        tvRevenuePrev   = view.findViewById(R.id.tvRevenuePrev)
        chartRevenueMini = view.findViewById(R.id.chartRevenueMini)

        // Header
        tvGreeting  = view.findViewById(R.id.tvGreeting)
        tvShopName  = view.findViewById(R.id.tvShopName)
        tvInitials  = view.findViewById(R.id.tvInitials)

        // Metric grid
        tvBills           = view.findViewById(R.id.tvBills)
        tvBillsGrowth     = view.findViewById(R.id.tvBillsGrowth)
        tvAverage         = view.findViewById(R.id.tvAverage)
        tvAverageGrowth   = view.findViewById(R.id.tvAverageGrowth)
        tvReturns         = view.findViewById(R.id.tvReturns)
        tvReturnsLabel    = view.findViewById(R.id.tvReturnsLabel)
        tvCancelled       = view.findViewById(R.id.tvCancelled)
        tvCancelledAmount = view.findViewById(R.id.tvCancelledAmount)

        // Payment split text
        tvCashAmount   = view.findViewById(R.id.tvCashAmount)
        tvCashPct      = view.findViewById(R.id.tvCashPct)
        tvUpiAmount    = view.findViewById(R.id.tvUpiAmount)
        tvUpiPct       = view.findViewById(R.id.tvUpiPct)
        tvCardAmount   = view.findViewById(R.id.tvCardAmount)
        tvCardPct      = view.findViewById(R.id.tvCardPct)
        tvCreditAmount = view.findViewById(R.id.tvCreditAmount)
        tvCreditPct    = view.findViewById(R.id.tvCreditPct)

        // Payment progress bars
        vProgCashFill    = view.findViewById(R.id.vProgCashFill)
        vProgCashSpacer  = view.findViewById(R.id.vProgCashSpacer)
        vProgUpiFill     = view.findViewById(R.id.vProgUpiFill)
        vProgUpiSpacer   = view.findViewById(R.id.vProgUpiSpacer)
        vProgCardFill    = view.findViewById(R.id.vProgCardFill)
        vProgCardSpacer  = view.findViewById(R.id.vProgCardSpacer)
        vProgCreditFill  = view.findViewById(R.id.vProgCreditFill)
        vProgCreditSpacer = view.findViewById(R.id.vProgCreditSpacer)

        // Date pill
        tvDate = view.findViewById(R.id.tvDate)
        tvDate.text = SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date())

        // Rounded pill fills for progress bars
        setRoundedFill(vProgCashFill,   R.color.ov_green_cash)
        setRoundedFill(vProgUpiFill,    R.color.ov_purple)
        setRoundedFill(vProgCardFill,   R.color.ov_blue)
        setRoundedFill(vProgCreditFill, R.color.ov_amber)

        tvGreeting.text = greeting()
        loadShopName()
        syncFilterFromActivity()
        loadKPI()
    }

    override fun onFilterChanged(
        filter: ReportFilter,
        startDate: String?,
        endDate: String?
    ) {
        currentFilter = filter
        customStartDate = startDate
        customEndDate = endDate
        loadKPI()
    }

    // 🔥 H4 FIX: ViewPager2 creates fragments lazily — pick up filter set before tab existed.
    private fun syncFilterFromActivity() {
        (activity as? com.example.easy_billing.ReportsActivity)?.let {
            currentFilter = it.currentFilter
            customStartDate = it.customStart
            customEndDate = it.customEnd
        }
    }

    // ── Header helpers ──────────────────────────────────────────────────────

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else      -> "Good evening"
        }
    }

    private fun initials(name: String): String {
        val words = name.trim().split(" ").filter { it.isNotBlank() }
        return when {
            words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
            words.size == 1 -> words[0].take(2).uppercase()
            else            -> "?"
        }
    }

    private fun loadShopName() {
        lifecycleScope.launch {
            val token = requireActivity()
                .getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch
            try {
                val profile = RetrofitClient.api.getProfile(token)
                tvShopName.text = profile.shop_name
                tvInitials.text = initials(profile.shop_name)
            } catch (_: Exception) {
                tvShopName.text = "Your Store"
                tvInitials.text = "?"
            }
        }
    }

    // ── KPI load ────────────────────────────────────────────────────────────

    private fun loadKPI() {
        lifecycleScope.launch {
            val token = requireActivity()
                .getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {
                val calendar = AppTime.calendar()
                var start: String? = null
                var end: String? = null
                var type = ""

                when (currentFilter) {
                    ReportFilter.TODAY -> {
                        type = "today"
                    }
                    ReportFilter.WEEK -> {
                        // Send named type so backend can compute prev-week comparison
                        type = "week"
                    }
                    ReportFilter.MONTH -> {
                        type = "month"
                    }
                    ReportFilter.YEAR -> {
                        type = "year"
                    }
                    ReportFilter.CUSTOM -> {
                        if (customStartDate != null && customEndDate != null) {
                            start = customStartDate
                            end = customEndDate
                            type = "custom"
                        } else {
                            return@launch
                        }
                    }
                }

                val data = RetrofitClient.api.getOverview(token, type, start, end)
                bindUI(data)

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "Failed to load overview", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindUI(data: OverviewResponse) {
        val ctx = requireContext()

        // ── Hero card ────────────────────────────────────────────────────────
        tvRevenue.text = CurrencyHelper.format(ctx, data.total_revenue)
        if (data.prev_revenue > 0) {
            tvRevenuePrev.visibility = View.VISIBLE
            tvRevenuePrev.text = "prev ${CurrencyHelper.format(ctx, data.prev_revenue)}"
        } else {
            tvRevenuePrev.visibility = View.GONE
        }

        setHeroGrowth(tvRevenueGrowth, data.total_revenue, data.prev_revenue)
        setupSparkline(data.sparkline)

        // ── Metric grid ──────────────────────────────────────────────────────
        tvBills.text   = data.total_bills.toString()
        tvAverage.text = CurrencyHelper.format(ctx, data.average_bill)
        setGrowth(tvBillsGrowth, data.total_bills.toDouble(), data.prev_bills.toDouble())
        setGrowth(tvAverageGrowth, data.average_bill, data.prev_avg)

        // Returns
        tvReturns.text = CurrencyHelper.format(ctx, data.returns_total)
        tvReturnsLabel.text = "credit/debit notes"

        // Cancelled
        tvCancelled.text = data.cancelled_count.toString()
        tvCancelledAmount.text = if (data.cancelled_amount > 0)
            CurrencyHelper.format(ctx, data.cancelled_amount) + " voided"
        else "none this period"

        // ── Payment split ────────────────────────────────────────────────────
        val zero = CurrencyHelper.format(ctx, 0.0)
        tvCashAmount.text   = zero; tvCashPct.text   = "0%"
        tvUpiAmount.text    = zero; tvUpiPct.text    = "0%"
        tvCardAmount.text   = zero; tvCardPct.text   = "0%"
        tvCreditAmount.text = zero; tvCreditPct.text = "0%"

        var pctCash = 0f; var pctUpi = 0f; var pctCard = 0f; var pctCredit = 0f

        for (item in data.payment_split) {
            when (item.method.lowercase().trim()) {
                "cash" -> {
                    tvCashAmount.text = CurrencyHelper.format(ctx, item.revenue)
                    tvCashPct.text    = "${item.percent}% of total"
                    pctCash           = item.percent.toFloat()
                }
                "upi" -> {
                    tvUpiAmount.text = CurrencyHelper.format(ctx, item.revenue)
                    tvUpiPct.text    = "${item.percent}% of total"
                    pctUpi           = item.percent.toFloat()
                }
                "card" -> {
                    tvCardAmount.text = CurrencyHelper.format(ctx, item.revenue)
                    tvCardPct.text    = "${item.percent}% of total"
                    pctCard           = item.percent.toFloat()
                }
                "credit" -> {
                    tvCreditAmount.text = CurrencyHelper.format(ctx, item.revenue)
                    tvCreditPct.text    = "${item.percent}% of total"
                    pctCredit           = item.percent.toFloat()
                }
            }
        }

        // Update individual progress bars
        updateProgressBars(pctCash, pctUpi, pctCard, pctCredit)
    }

    /** Assigns a capsule-shaped GradientDrawable so both ends of the fill are rounded. */
    private fun setRoundedFill(v: View, colorRes: Int) {
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 100f * resources.displayMetrics.density
            setColor(requireContext().getColor(colorRes))
        }
    }

    private fun setBarWeight(fill: View, spacer: View, pct: Float) {
        val safeP = pct.coerceIn(0f, 100f)
        (fill.layoutParams   as LinearLayout.LayoutParams).weight = safeP
        (spacer.layoutParams as LinearLayout.LayoutParams).weight = 100f - safeP
        fill.requestLayout()
        spacer.requestLayout()
    }

    private fun updateProgressBars(cash: Float, upi: Float, card: Float, credit: Float) {
        setBarWeight(vProgCashFill,   vProgCashSpacer,   cash)
        setBarWeight(vProgUpiFill,    vProgUpiSpacer,    upi)
        setBarWeight(vProgCardFill,   vProgCardSpacer,   card)
        setBarWeight(vProgCreditFill, vProgCreditSpacer, credit)
    }

    // ── Growth chip styling ─────────────────────────────────────────────────

    /** Growth chip shown on WHITE metric cards — green/red on light bg */
    private fun setGrowth(view: TextView, current: Double, previous: Double) {
        if (previous == 0.0) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val hPad = 9.dp(); val vPad = 4.dp()
        val change = ((current - previous) / previous) * 100
        val isPositive = change >= 0
        view.text = if (isPositive) "↑ ${"%.1f".format(change)}%"
                    else            "↓ ${"%.1f".format(kotlin.math.abs(change))}%"
        view.setTextColor(requireContext().getColor(if (isPositive) R.color.ov_green else R.color.ov_red))
        view.setBackgroundResource(if (isPositive) R.drawable.bg_growth_positive else R.drawable.bg_growth_negative)
        view.setPadding(hPad, vPad, hPad, vPad)
    }

    /** Growth chip shown on the NAVY hero card — light-tinted text on translucent bg */
    private fun setHeroGrowth(view: TextView, current: Double, previous: Double) {
        if (previous == 0.0) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val hPad = 12.dp(); val vPad = 4.dp()
        val change = ((current - previous) / previous) * 100
        val isPositive = change >= 0
        view.text = if (isPositive) "↑ ${"%.1f".format(change)}%"
                    else            "↓ ${"%.1f".format(kotlin.math.abs(change))}%"
        view.setTextColor(requireContext().getColor(
            if (isPositive) R.color.ov_hero_growth_pos else R.color.ov_hero_growth_neg))
        view.setBackgroundResource(
            if (isPositive) R.drawable.bg_ov_hero_growth_pos else R.drawable.bg_ov_hero_growth_neg)
        view.setPadding(hPad, vPad, hPad, vPad)
    }

    // ── Sparkline ───────────────────────────────────────────────────────────

    /**
     * Draws the 7-point sparkline (last 7 calendar days, net of returns)
     * on the dark hero card. Line is always light-purple — the delta chip
     * already communicates direction.
     */
    private fun setupSparkline(values: List<Double>) {
        chartRevenueMini.clear()

        val entries = values.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }

        val dataSet = LineDataSet(entries, "").apply {
            color        = sparklineColor
            lineWidth    = 2f
            setDrawCircles(false)
            setDrawValues(false)
            mode         = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillDrawable = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(20, 255, 255, 255), Color.TRANSPARENT)
            )
        }

        chartRevenueMini.apply {
            data                  = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled      = false
            axisLeft.isEnabled    = false
            axisRight.isEnabled   = false
            xAxis.isEnabled       = false
            setTouchEnabled(false)
            setBackgroundColor(Color.TRANSPARENT)
            animateX(600)
            invalidate()
        }
    }
}
