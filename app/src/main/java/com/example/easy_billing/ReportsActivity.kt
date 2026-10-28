package com.example.easy_billing

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.ui.ThemedDropdown
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ReportsActivity : BaseActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabOverview: android.view.View
    private lateinit var tabTrends: android.view.View
    private lateinit var tabBusyTimes: android.view.View
    private lateinit var tabBestSellers: android.view.View
    private lateinit var tvTabOverview: android.widget.TextView
    private lateinit var tvTabTrends: android.widget.TextView
    private lateinit var tvTabBusyTimes: android.widget.TextView
    private lateinit var tvTabBestSellers: android.widget.TextView
    private lateinit var indicatorOverview: android.view.View
    private lateinit var indicatorTrends: android.view.View
    private lateinit var indicatorBusyTimes: android.view.View
    private lateinit var indicatorBestSellers: android.view.View
    private lateinit var tvReportDate: android.widget.TextView

    // 🔥 H4 FIX: single source of truth for the active filter.
    // ViewPager2 creates fragments lazily — fragments read this in
    // onViewCreated so late-created tabs use the selected filter
    // instead of defaulting to TODAY.
    var currentFilter = ReportFilter.TODAY
        private set
    var customStart: String? = null
        private set
    var customEnd: String? = null
        private set

    // 🔥 H5 FIX: MaterialDatePicker returns UTC-midnight millis — format
    // them in UTC or the date shifts back a day west of UTC. Locale.US
    // guarantees ASCII digits for the API.
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)
        // Restore the selected chip / custom range after rotation
        savedInstanceState?.getString("rep_filter")?.let { n ->
            currentFilter = try { ReportFilter.valueOf(n) } catch (e: Exception) { ReportFilter.TODAY }
            customStart = savedInstanceState.getString("rep_start")
            customEnd = savedInstanceState.getString("rep_end")
            if (currentFilter == ReportFilter.CUSTOM && (customStart == null || customEnd == null))
                currentFilter = ReportFilter.TODAY
        }
        com.example.easy_billing.util.UserEventLogger.logAction("Reports", "opened")

        // ✅ Toolbar
        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        // ✅ Views
        viewPager = findViewById(R.id.viewPager)
        tabOverview = findViewById(R.id.tabOverview)
        tabTrends = findViewById(R.id.tabTrends)
        tabBusyTimes = findViewById(R.id.tabBusyTimes)
        tabBestSellers = findViewById(R.id.tabBestSellers)
        tvTabOverview = findViewById(R.id.tvTabOverview)
        tvTabTrends = findViewById(R.id.tvTabTrends)
        tvTabBusyTimes = findViewById(R.id.tvTabBusyTimes)
        tvTabBestSellers = findViewById(R.id.tvTabBestSellers)
        indicatorOverview = findViewById(R.id.indicatorOverview)
        indicatorTrends = findViewById(R.id.indicatorTrends)
        indicatorBusyTimes = findViewById(R.id.indicatorBusyTimes)
        indicatorBestSellers = findViewById(R.id.indicatorBestSellers)
        tvReportDate = findViewById(R.id.tvReportDate)
        tvReportDate.text = SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date())

        setupViewPager()
        setupFilters()
        setupSendReportButton()
    }

    /** Lets a child fragment (e.g. Overview's "busiest time" teaser) jump to another tab. */
    fun goToTab(position: Int) {
        viewPager.setCurrentItem(position, true)
        selectTab(position)
    }

    // Same plain underline-tab visual language as the Overview/Trend switch
    // inside Profit Analytics: active tab gets dark bold text + a teal bar
    // underneath, the other three fall back to muted medium text + no bar.
    private fun selectTab(position: Int) {
        val labels = listOf(tvTabOverview, tvTabTrends, tvTabBusyTimes, tvTabBestSellers)
        val indicators = listOf(indicatorOverview, indicatorTrends, indicatorBusyTimes, indicatorBestSellers)
        labels.forEachIndexed { index, label ->
            val indicator = indicators[index]
            if (index == position) {
                label.setTextColor(Color.parseColor("#1A1A18"))
                label.typeface = androidx.core.content.res.ResourcesCompat
                    .getFont(this, R.font.googlesans_semibold)
                indicator.setBackgroundColor(Color.parseColor("#0F6E56"))
            } else {
                label.setTextColor(Color.parseColor("#7C8782"))
                label.typeface = androidx.core.content.res.ResourcesCompat
                    .getFont(this, R.font.googlesans_medium)
                indicator.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    // ---------------- VIEWPAGER ----------------

    private fun setupViewPager() {

        viewPager.adapter = ReportsPagerAdapter(this)
        selectTab(viewPager.currentItem)

        tabOverview.setOnClickListener { viewPager.setCurrentItem(0, true) }
        tabTrends.setOnClickListener { viewPager.setCurrentItem(1, true) }
        tabBusyTimes.setOnClickListener { viewPager.setCurrentItem(2, true) }
        tabBestSellers.setOnClickListener { viewPager.setCurrentItem(3, true) }

        // Keep the active pill in sync if the page changes some other way
        // (e.g. a fragment programmatically navigating the pager).
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectTab(position)
            }
        })
    }

    // ---------------- FILTERS ----------------

    private fun setupFilters() {

        val chipToday = findViewById<Chip>(R.id.chipToday)
        val chipWeek = findViewById<Chip>(R.id.chipWeek)
        val chipMonth = findViewById<Chip>(R.id.chipMonth)
        val chipYear = findViewById<Chip>(R.id.chipYear)
        val chipCustom = findViewById<Chip>(R.id.chipCustom)

        chipToday?.setOnClickListener {
            selectChip(chipToday)
            notifyFragments(ReportFilter.TODAY)
        }

        chipWeek?.setOnClickListener {
            selectChip(chipWeek)
            notifyFragments(ReportFilter.WEEK)
        }

        chipMonth?.setOnClickListener {
            selectChip(chipMonth)
            notifyFragments(ReportFilter.MONTH)
        }

        chipYear?.setOnClickListener {
            selectChip(chipYear)
            notifyFragments(ReportFilter.YEAR)
        }

        chipCustom?.setOnClickListener {
            openCustomDatePicker()
        }

        // Apply initial selected state — XML state lists are ignored by M3
        chipFor(currentFilter)?.let { selectChip(it) }
    }

    // ── Chip selection ───────────────────────────────────────────────────
    // Material3 overrides chipBackgroundColor via chipSurfaceColor from the
    // theme, so XML color state lists are silently ignored. We drive every
    // visual property programmatically here instead.

    private fun allChips() = listOf(
        findViewById<Chip>(R.id.chipToday),
        findViewById<Chip>(R.id.chipWeek),
        findViewById<Chip>(R.id.chipMonth),
        findViewById<Chip>(R.id.chipYear),
        findViewById<Chip>(R.id.chipCustom)
    ).filterNotNull()

    private fun selectChip(selectedChip: Chip) {
        allChips().forEach { chip -> applyChipStyle(chip, chip == selectedChip) }
    }

    // Same hashed-accent-per-chip concept as the dashboard's category rail —
    // each chip's label hashes to a stable color from this palette instead
    // of every selected chip looking identically gold.
    private val dateChipPalette = listOf(
        "#0F6E56", "#B23A3A", "#8A6526", "#185FA5",
        "#534AB7", "#D85A30", "#3B6D11", "#993556"
    )

    private fun dateChipColor(label: CharSequence?): Int =
        Color.parseColor(
            dateChipPalette[((label?.toString() ?: "").hashCode() and 0x7FFFFFFF) % dateChipPalette.size]
        )

    private fun applyChipStyle(chip: Chip, selected: Boolean) {
        chip.chipBackgroundColor = ColorStateList.valueOf(Color.WHITE)
        chip.isChipIconVisible = false
        if (selected) {
            val accent = dateChipColor(chip.text)
            chip.chipStrokeColor = ColorStateList.valueOf(accent)
            chip.setTextColor(accent)
            chip.setTypeface(null, Typeface.BOLD)
        } else {
            chip.chipStrokeColor = ColorStateList.valueOf(Color.parseColor("#D5DDD8"))
            chip.setTextColor(Color.parseColor("#5B6660"))
            chip.setTypeface(null, Typeface.NORMAL)
        }
    }

    // ---------------- CUSTOM DATE PICKER ----------------

    private fun openCustomDatePicker() {

        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.reports_pick_range))
            .build()

        picker.show(supportFragmentManager, "DATE_RANGE")

        // Only highlight "Custom" once a range is actually chosen —
        // cancelling the picker keeps the previous chip and its data.
        picker.addOnPositiveButtonClickListener {

            val start = sdf.format(Date(it.first))
            val end = sdf.format(Date(it.second))

            findViewById<Chip>(R.id.chipCustom)?.let { c -> selectChip(c) }
            notifyFragments(ReportFilter.CUSTOM, start, end)
        }
    }

    private fun chipFor(filter: ReportFilter): Chip? = findViewById(
        when (filter) {
            ReportFilter.TODAY -> R.id.chipToday
            ReportFilter.WEEK -> R.id.chipWeek
            ReportFilter.MONTH -> R.id.chipMonth
            ReportFilter.YEAR -> R.id.chipYear
            ReportFilter.CUSTOM -> R.id.chipCustom
        }
    )

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("rep_filter", currentFilter.name)
        outState.putString("rep_start", customStart)
        outState.putString("rep_end", customEnd)
    }

    // ---------------- SHARE REPORT AS PDF ----------------

    private fun setupSendReportButton() {
        findViewById<MaterialButton>(R.id.btnSendReport)?.setOnClickListener {
            shareReportPdf()
        }
    }

    /** Dates (yyyy-MM-dd) covered by the selected chip. */
    fun rangeForFilter(): Pair<String, String>? {
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = com.example.easy_billing.util.AppTime.calendar()
        iso.timeZone = cal.timeZone
        return when (currentFilter) {
            ReportFilter.TODAY -> iso.format(cal.time).let { it to it }
            ReportFilter.WEEK -> {
                cal.add(Calendar.DAY_OF_MONTH, -(cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
                val s = iso.format(cal.time)
                cal.add(Calendar.DAY_OF_MONTH, 6)
                s to iso.format(cal.time)
            }
            ReportFilter.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val s = iso.format(cal.time)
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                s to iso.format(cal.time)
            }
            ReportFilter.YEAR -> {
                cal.set(Calendar.MONTH, Calendar.JANUARY); cal.set(Calendar.DAY_OF_MONTH, 1)
                val s = iso.format(cal.time)
                cal.set(Calendar.MONTH, Calendar.DECEMBER); cal.set(Calendar.DAY_OF_MONTH, 31)
                s to iso.format(cal.time)
            }
            ReportFilter.CUSTOM -> {
                val s = customStart; val e = customEnd
                if (s != null && e != null) s to e else null
            }
        }
    }

    /** Day/month rows, scrap rows and unsold products for the longer PDF; any part may come back empty. */
    private suspend fun buildPdfExtra(
        token: String, filter: ReportFilter, s: String, e: String,
        products: List<com.example.easy_billing.network.TopProductResponse>
    ): com.example.easy_billing.util.ReportsPdfExtra {
        val api = RetrofitClient.api
        val today = com.example.easy_billing.util.AppTime.todayIso()
        var dayRows = emptyList<Triple<String, Int, Double>>()
        var byMonth = false
        try {
            val iso = com.example.easy_billing.util.AppTime.isoDate()
            if (filter == ReportFilter.YEAR) {
                val ym = com.example.easy_billing.util.AppTime.formatter("yyyy-MM")
                val label = com.example.easy_billing.util.AppTime.formatter("MMM yyyy")
                dayRows = api.getMonthlyReport(token)
                    .filter { it.month.startsWith(s.take(4)) && it.month.take(7) <= today.take(7) }
                    .sortedBy { it.month }
                    .map { Triple(runCatching { label.format(ym.parse(it.month.take(7))!!) }.getOrDefault(it.month), it.bills, it.revenue) }
                byMonth = true
            } else if (s != e) {
                val label = com.example.easy_billing.util.AppTime.formatter("EEE, dd MMM")
                dayRows = api.getDailyReport(token)
                    .filter { it.date >= s && it.date <= e && it.date <= today }
                    .sortedBy { it.date }
                    .map { Triple(runCatching { label.format(iso.parse(it.date)!!) }.getOrDefault(it.date), it.bills, it.revenue) }
            }
        } catch (ex: Exception) { /* section is skipped */ }

        val scrap = try {
            com.example.easy_billing.ScrapData.load(this).rows.filter { it.date >= s && it.date <= e }
        } catch (ex: Exception) { emptyList() }

        val sold = products.map { p ->
            (p.variant?.takeIf { it.isNotBlank() }?.let { "${p.product} ($it)" } ?: p.product).trim().lowercase()
        }.toSet()
        val notSold = try {
            com.example.easy_billing.db.AppDatabase.getDatabase(this).productDao().getAll()
                .map { p -> p.variant?.takeIf { it.isNotBlank() }?.let { "${p.name} ($it)" } ?: p.name }
                .filter { it.trim().lowercase() !in sold }
                .distinct()
        } catch (ex: Exception) { emptyList() }

        // Money Book figures for the same period, same rules as the Money card on the Overview tab.
        var mWithin: com.example.easy_billing.util.MoneyTotals? = null
        var mBalance: com.example.easy_billing.util.MoneyTotals? = null
        var mCaption = ""
        var mPending = 0.0
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dao = com.example.easy_billing.db.AppDatabase.getDatabase(this@ReportsActivity).moneyEntryDao()
                val (from, to) = com.example.easy_billing.util.MoneyReport.dayBounds(s, e)
                mPending = dao.pendingInTotal()
                mWithin = com.example.easy_billing.util.MoneyReport.totals(dao.sumsBetween(from, to, listOf("OPENING", "TRANSFER")))
                mBalance = com.example.easy_billing.util.MoneyReport.totals(dao.sumsBetween(0L, to, emptyList()))
                mCaption = if (to > com.example.easy_billing.util.appNow()) getString(R.string.money_balance_now)
                else getString(R.string.money_balance_on,
                    com.example.easy_billing.util.AppTime.formatter("dd MMM yyyy").format(java.util.Date(to - 1)))
            }
        } catch (ex: Exception) { mWithin = null; mBalance = null }

        return com.example.easy_billing.util.ReportsPdfExtra(dayRows, byMonth, scrap, notSold, mWithin, mBalance, mCaption, mPending)
    }

    private fun shareReportPdf() {
        val range = rangeForFilter() ?: return
        val filter = currentFilter
        val btn = findViewById<MaterialButton>(R.id.btnSendReport)
        btn?.isEnabled = false

        lifecycleScope.launch {
            try {
                val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
                if (token == null) {
                    Toast.makeText(this@ReportsActivity,
                        getString(R.string.reports_toast_auth_error), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val (s, e) = range
                val overviewType = when (filter) {
                    ReportFilter.TODAY -> "today"
                    ReportFilter.WEEK -> "week"
                    ReportFilter.MONTH -> "month"
                    ReportFilter.YEAR -> "year"
                    ReportFilter.CUSTOM -> "custom"
                }
                val api = RetrofitClient.api
                val overview = if (filter == ReportFilter.CUSTOM)
                    api.getOverview(token, "custom", s, e)
                else api.getOverview(token, overviewType)

                if (overview.total_bills == 0 && overview.total_revenue == 0.0) {
                    Toast.makeText(this@ReportsActivity,
                        getString(R.string.reports_share_no_data), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val products = try {
                    if (filter == ReportFilter.TODAY) api.getTopProducts(token, "today", null, null, "revenue")
                    else api.getTopProducts(token, "custom", s, e, "revenue")
                } catch (ex: Exception) { emptyList() }
                val hours = try {
                    if (filter == ReportFilter.TODAY) api.getPeakHours(token, "today")
                    else api.getPeakHours(token, "custom", s, e)
                } catch (ex: Exception) { emptyList() }

                val extra = buildPdfExtra(token, filter, s, e, products)

                val pretty = com.example.easy_billing.util.AppTime.formatter("dd MMM yyyy")
                val iso = com.example.easy_billing.util.AppTime.isoDate()
                val a = pretty.format(iso.parse(s)!!)
                val b = pretty.format(iso.parse(e)!!)
                val periodText = if (s == e) a else "$a – $b"

                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.easy_billing.util.ReportsPdfGenerator
                        .generate(this@ReportsActivity, overview, products, hours, periodText, extra)
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@ReportsActivity, "$packageName.provider", file)
                startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, null))
            } catch (ex: Exception) {
                com.example.easy_billing.util.UserEventLogger.logError(
                    "Reports", "share_report_failed: ${ex.javaClass.simpleName}")
                Toast.makeText(this@ReportsActivity,
                    getString(R.string.reports_share_failed), Toast.LENGTH_SHORT).show()
            } finally {
                btn?.isEnabled = true
            }
        }
    }

    // ---------------- FILTER COMMUNICATION ----------------

    private fun notifyFragments(
        filter: ReportFilter,
        start: String? = null,
        end: String? = null
    ) {

        // 🔥 H4 FIX: remember the selection for fragments created later
        currentFilter = filter
        customStart = start
        customEnd = end

        supportFragmentManager.fragments.forEach { fragment ->

            // 🔥 Handle ViewPager fragments
            fragment.childFragmentManager.fragments.forEach { child ->

                if (child is Filterable) {
                    child.onFilterChanged(filter, start, end)
                }
            }

            // fallback (if directly attached)
            if (fragment is Filterable) {
                fragment.onFilterChanged(filter, start, end)
            }
        }
    }
}