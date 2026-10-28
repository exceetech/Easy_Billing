package com.example.easy_billing

import com.example.easy_billing.R

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Host Activity for the Profit Analytics flow: owns the shared toolbar and
 * the Overview/Trend tab-switch row, and hosts [ProfitOverviewFragment] /
 * [ProfitTrendFragment] side by side (added once, toggled with hide()/
 * show()) so switching tabs is instant and symmetric in both directions --
 * no Activity finish/recreate flicker either way, unlike the previous
 * two-Activity (ProfitActivity -> ProfitChartActivity) setup.
 */
class ProfitActivity : AppCompatActivity() {

    private lateinit var tabNavOverview: android.widget.LinearLayout
    private lateinit var tabNavTrend: android.widget.LinearLayout
    private lateinit var tvTabOverview: TextView
    private lateinit var tvTabTrend: TextView
    private lateinit var tabOverviewIndicator: android.view.View
    private lateinit var tabNavScrap: android.widget.LinearLayout
    private lateinit var tvTabScrap: TextView
    private lateinit var tabScrapIndicator: android.view.View
    private lateinit var tabTrendIndicator: android.view.View

    private lateinit var profitPager: androidx.viewpager2.widget.ViewPager2

    // Pages are created lazily by the pager, so the fragments are captured
    // by a lifecycle callback (below) and may be null until then.
    private var overviewFragment: ProfitOverviewFragment? = null
    private var trendFragment: ProfitTrendFragment? = null
    private var scrapFragment: ProfitScrapFragment? = null

    // Current shared date filter, re-applied to a page whenever it is (re)created.
    private var currentFilter = "all"
    private var currentStart: String? = null
    private var currentEnd: String? = null

    private fun applyToPages(filter: String, start: String? = null, end: String? = null) {
        currentFilter = filter
        currentStart = start
        currentEnd = end
        overviewFragment?.applyFilter(filter, start, end)
        trendFragment?.applyFilter(filter, start, end)
        scrapFragment?.applyFilter(filter, start, end)
    }

    private val pageCallbacks = object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: androidx.fragment.app.FragmentManager,
            f: androidx.fragment.app.Fragment,
            v: android.view.View,
            savedInstanceState: Bundle?
        ) {
            when (f) {
                is ProfitOverviewFragment -> {
                    overviewFragment = f
                    if (currentFilter != "all") f.applyFilter(currentFilter, currentStart, currentEnd)
                }
                is ProfitTrendFragment -> {
                    trendFragment = f
                    f.applyFilter(currentFilter, currentStart, currentEnd)
                }
                is ProfitScrapFragment -> {
                    scrapFragment = f
                    f.applyFilter(currentFilter, currentStart, currentEnd)
                }
            }
        }
    }

    private val destroyCallbacks = object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewDestroyed(
            fm: androidx.fragment.app.FragmentManager,
            f: androidx.fragment.app.Fragment
        ) {
            if (f === overviewFragment) overviewFragment = null
            if (f === trendFragment) trendFragment = null
            if (f === scrapFragment) scrapFragment = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pf", currentFilter)
        outState.putString("ps", currentStart)
        outState.putString("pe", currentEnd)
    }

    // ================= SHARED DATE FILTER (moved from ProfitOverviewFragment) =================
    private lateinit var btnToday: com.google.android.material.chip.Chip
    private lateinit var btnWeek: com.google.android.material.chip.Chip
    private lateinit var btnMonth: com.google.android.material.chip.Chip
    private lateinit var btnAll: com.google.android.material.chip.Chip
    private lateinit var btnCustom: com.google.android.material.chip.Chip
    private lateinit var dateChips: List<Pair<com.google.android.material.chip.Chip, String>>

    // Deliberately NOT a BaseActivity (avoids BaseActivity's forced landscape
    // re-orientation), so the system bars are hidden locally here instead —
    // same immersive treatment every other screen in the app gets.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    // ================= ON CREATE =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profit)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayHomeAsUpEnabled(true)
        }
        // Themed back arrow (matches the rest of the app).
        toolbar.setNavigationIcon(R.drawable.ic_back_arrow)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        tabNavOverview = findViewById(R.id.tabNavOverview)
        tabNavTrend = findViewById(R.id.tabNavTrend)
        tvTabOverview = findViewById(R.id.tvTabOverview)
        tvTabTrend = findViewById(R.id.tvTabTrend)
        tabOverviewIndicator = findViewById(R.id.tabOverviewIndicator)
        tabTrendIndicator = findViewById(R.id.tabTrendIndicator)
        tabNavScrap = findViewById(R.id.tabNavScrap)
        tvTabScrap = findViewById(R.id.tvTabScrap)
        tabScrapIndicator = findViewById(R.id.tabScrapIndicator)

        if (savedInstanceState != null) {
            currentFilter = savedInstanceState.getString("pf") ?: "all"
            currentStart = savedInstanceState.getString("ps")
            currentEnd = savedInstanceState.getString("pe")
        }
        supportFragmentManager.registerFragmentLifecycleCallbacks(pageCallbacks, false)
        supportFragmentManager.registerFragmentLifecycleCallbacks(destroyCallbacks, false)

        setupDateFilters()

        // Swipeable pages, same as the Reports screen: Overview <-> Trend.
        profitPager = findViewById(R.id.profitPager)
        profitPager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int): androidx.fragment.app.Fragment =
                when (position) {
                    0 -> ProfitOverviewFragment()
                    1 -> ProfitTrendFragment()
                    else -> ProfitScrapFragment()
                }
        }
        profitPager.offscreenPageLimit = 2
        profitPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                setActiveTab(position)
            }
        })
        setActiveTab(profitPager.currentItem)

        tabNavTrend.setOnClickListener { profitPager.setCurrentItem(1, true) }
        tabNavOverview.setOnClickListener { profitPager.setCurrentItem(0, true) }
        tabNavScrap.setOnClickListener { profitPager.setCurrentItem(2, true) }
    }

    // ================= SHARED DATE FILTER =================
    // Owns the mutual-exclusion, per-chip coloring and date-range picker
    // that used to live in ProfitOverviewFragment -- now the filter is
    // shared by both tabs, it has to live in the host. Logic copied
    // verbatim from the old fragment code, adapted to the Activity.

    private fun setupDateFilters() {
        btnToday = findViewById(R.id.btnToday)
        btnWeek = findViewById(R.id.btnWeek)
        btnMonth = findViewById(R.id.btnMonth)
        btnAll = findViewById(R.id.btnAll)
        btnCustom = findViewById(R.id.btnCustom)

        dateChips = listOf(
            btnToday to "Today",
            btnWeek to "Week",
            btnMonth to "Month",
            btnAll to "All",
            btnCustom to "Custom"
        )

        btnToday.setOnClickListener {
            selectDateChip(btnToday)
            applyToPages("today")
        }
        btnWeek.setOnClickListener {
            selectDateChip(btnWeek)
            applyToPages("week")
        }
        btnMonth.setOnClickListener {
            selectDateChip(btnMonth)
            applyToPages("month")
        }
        btnAll.setOnClickListener {
            selectDateChip(btnAll)
            applyToPages("all")
        }
        btnCustom.setOnClickListener {
            // The chip toggles itself on tap; keep the current filter's chip highlighted until
            // a range is actually picked (cancelling the picker changes nothing).
            selectDateChip(chipFor(currentFilter))
            openDatePicker()
        }

        // After a rotation / restore, highlight the chip of the filter that is really applied.
        selectDateChip(chipFor(currentFilter))
        refreshChipColors()
    }

    private fun chipFor(filter: String): com.google.android.material.chip.Chip = when (filter) {
        "today" -> btnToday
        "week" -> btnWeek
        "month" -> btnMonth
        "custom" -> btnCustom
        else -> btnAll
    }

    private val dateChipPalette = listOf(
        "#0F6E56", "#B23A3A", "#8A6526", "#185FA5",
        "#534AB7", "#D85A30", "#3B6D11", "#993556"
    )

    private fun dateChipColor(label: String): Int =
        Color.parseColor(
            dateChipPalette[(label.hashCode() and 0x7FFFFFFF) % dateChipPalette.size]
        )

    private fun refreshChipColors() {
        dateChips.forEach { (chip, label) -> styleDateChip(chip, chip.isChecked, label) }
    }

    // Only one date chip is ever checked at a time -- enforce it ourselves,
    // same as the old fragment-owned version did.
    private fun selectDateChip(selected: com.google.android.material.chip.Chip) {
        dateChips.forEach { (chip, _) -> chip.isChecked = (chip === selected) }
        refreshChipColors()
    }

    private fun styleDateChip(chip: com.google.android.material.chip.Chip, selected: Boolean, label: String) {
        val accent = dateChipColor(label)
        val strokeColor = if (selected) accent else Color.parseColor("#D5DDD8")
        val textColor = if (selected) accent else Color.parseColor("#5B6660")
        chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(strokeColor)
        chip.setTextColor(textColor)
    }

    private fun openDatePicker() {
        val constraints = com.google.android.material.datepicker.CalendarConstraints.Builder()
            .setValidator(
                com.google.android.material.datepicker.DateValidatorPointBackward.now()
            )
            .build()

        val picker = com.google.android.material.datepicker.MaterialDatePicker.Builder
            .dateRangePicker()
            .setTitleText("Select Date Range")
            .setCalendarConstraints(constraints)
            .build()

        // Shown via the Activity's own FragmentManager now (this is no
        // longer a Fragment-hosted control).
        picker.show(supportFragmentManager, "DATE")

        picker.addOnPositiveButtonClickListener {
            val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))

            utc.timeInMillis = it.first
            val startDate = utc.time

            utc.timeInMillis = it.second
            val endDate = utc.time

            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")

            val start = format.format(startDate)
            val end = format.format(endDate)

            selectDateChip(btnCustom)
            applyToPages("custom", start, end)
        }
    }

    /** Updates the single shared tab row's active/inactive visuals — dark
     *  bold text + teal underline for the active tab, muted text + no
     *  underline for the inactive one. Colors/sizes copied exactly from
     *  the previous per-page XML (activity_profit.xml's Overview-active
     *  state and activity_profit_chart.xml's Trend-active state). */
    private fun setActiveTab(position: Int) {
        val tabs = listOf(
            tvTabOverview to tabOverviewIndicator,
            tvTabTrend to tabTrendIndicator,
            tvTabScrap to tabScrapIndicator
        )
        tabs.forEachIndexed { i, (tv, indicator) ->
            val active = i == position
            tv.setTextColor(Color.parseColor(if (active) "#1A1A18" else "#7C8782"))
            tv.typeface = androidx.core.content.res.ResourcesCompat.getFont(
                this, if (active) R.font.googlesans_semibold else R.font.googlesans_medium
            )
            indicator.setBackgroundColor(Color.parseColor(if (active) "#0F6E56" else "#00000000"))
        }
    }

    // Offline-session-timeout coverage (see SessionTimeoutGuard for why this
    // isn't done via extending BaseActivity instead).
    override fun onResume() {
        super.onResume()
        com.example.easy_billing.util.SessionTimeoutGuard.start(this)
    }

    override fun onPause() {
        super.onPause()
        com.example.easy_billing.util.SessionTimeoutGuard.stop(this)
    }

    // Defensive backstop in case onPause is ever skipped by a future edit —
    // stop() is safe to call even if the guard was already stopped.
    override fun onDestroy() {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(pageCallbacks)
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(destroyCallbacks)
        com.example.easy_billing.util.SessionTimeoutGuard.stop(this)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        private const val TAG_OVERVIEW = "profit_overview"
        private const val TAG_TREND = "profit_trend"
    }
}
