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
    private lateinit var tabTrendIndicator: android.view.View

    private lateinit var overviewFragment: ProfitOverviewFragment
    private lateinit var trendFragment: ProfitTrendFragment

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

        setupDateFilters()

        if (savedInstanceState == null) {
            overviewFragment = ProfitOverviewFragment()
            trendFragment = ProfitTrendFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.profitFragmentContainer, overviewFragment, TAG_OVERVIEW)
                .add(R.id.profitFragmentContainer, trendFragment, TAG_TREND)
                .hide(trendFragment)
                .commit()
            // Force the transaction (and both fragments' onViewCreated,
            // which each still do their own first load -- Overview
            // defaults to "all", Trend to "today") to run synchronously
            // before we push the shared "all" filter into both, so
            // neither fragment is asked to apply a filter before its view
            // exists. Trend's independent "today" default gets overwritten
            // here to bring it in sync with Overview's "all" -- this one
            // extra call is a small, one-time cost (not a repeating
            // double-load) in exchange for both tabs reliably starting on
            // the same period.
            supportFragmentManager.executePendingTransactions()
            overviewFragment.applyFilter("all")
            trendFragment.applyFilter("all")
        } else {
            overviewFragment = supportFragmentManager
                .findFragmentByTag(TAG_OVERVIEW) as ProfitOverviewFragment
            trendFragment = supportFragmentManager
                .findFragmentByTag(TAG_TREND) as ProfitTrendFragment
            // Chips restore their own `isChecked` from saved state
            // automatically (default View behavior), so whichever chip the
            // user had selected is already checked here -- just repaint
            // colors to match so nothing looks stale post-rotation.
            refreshChipColors()
        }

        setActiveTab(isOverview = true)

        tabNavTrend.setOnClickListener {
            // Smooth crossfade, not a hard cut or a side-slide — this is a
            // tab switch, not drilling into a new screen. Both directions
            // use the identical transaction shape (just swapped), so
            // there's no asymmetry between going to Trend and coming back.
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .hide(overviewFragment)
                .show(trendFragment)
                .commit()
            setActiveTab(isOverview = false)
        }

        tabNavOverview.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .hide(trendFragment)
                .show(overviewFragment)
                .commit()
            setActiveTab(isOverview = true)
        }
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
            overviewFragment.applyFilter("today")
            trendFragment.applyFilter("today")
        }
        btnWeek.setOnClickListener {
            selectDateChip(btnWeek)
            overviewFragment.applyFilter("week")
            trendFragment.applyFilter("week")
        }
        btnMonth.setOnClickListener {
            selectDateChip(btnMonth)
            overviewFragment.applyFilter("month")
            trendFragment.applyFilter("month")
        }
        btnAll.setOnClickListener {
            selectDateChip(btnAll)
            overviewFragment.applyFilter("all")
            trendFragment.applyFilter("all")
        }
        btnCustom.setOnClickListener {
            openDatePicker()
        }

        refreshChipColors()
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
        val strokeColor = if (selected) accent else Color.parseColor("#E4DCC8")
        val textColor = if (selected) accent else Color.parseColor("#6E6A60")
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
            overviewFragment.applyFilter("custom", start, end)
            trendFragment.applyFilter("custom", start, end)
        }
    }

    /** Updates the single shared tab row's active/inactive visuals — dark
     *  bold text + teal underline for the active tab, muted text + no
     *  underline for the inactive one. Colors/sizes copied exactly from
     *  the previous per-page XML (activity_profit.xml's Overview-active
     *  state and activity_profit_chart.xml's Trend-active state). */
    private fun setActiveTab(isOverview: Boolean) {
        if (isOverview) {
            tvTabOverview.setTextColor(Color.parseColor("#1A1A18"))
            tvTabOverview.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(this, R.font.googlesans_semibold)
            tabOverviewIndicator.setBackgroundColor(Color.parseColor("#0F6E56"))

            tvTabTrend.setTextColor(Color.parseColor("#8C8576"))
            tvTabTrend.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(this, R.font.googlesans_medium)
            tabTrendIndicator.setBackgroundColor(Color.TRANSPARENT)
        } else {
            tvTabTrend.setTextColor(Color.parseColor("#1A1A18"))
            tvTabTrend.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(this, R.font.googlesans_semibold)
            tabTrendIndicator.setBackgroundColor(Color.parseColor("#0F6E56"))

            tvTabOverview.setTextColor(Color.parseColor("#8C8576"))
            tvTabOverview.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(this, R.font.googlesans_medium)
            tabOverviewIndicator.setBackgroundColor(Color.TRANSPARENT)
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
