package com.example.easy_billing

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.gstr1.Gstr1DraftEntity
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.gstr1.Gstr1Report
import com.example.easy_billing.gstr1.Gstr1SheetTabAdapter
import com.example.easy_billing.gstr1.Gstr1Validator
import com.example.easy_billing.viewmodel.Gstr1ViewModel

import com.example.easy_billing.gstr2.Gstr2DraftEntity
import com.example.easy_billing.gstr2.Gstr2Report
import com.example.easy_billing.gstr2.Gstr2SheetTabAdapter
import com.example.easy_billing.gstr2.Gstr2Validator
import com.example.easy_billing.gstr2.Gstr2DraftsAdapter
import com.example.easy_billing.viewmodel.Gstr2ViewModel

import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.card.MaterialCardView
import android.widget.ImageView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * GstReportsActivity — Full GSTR-1 Preparation & Export Screen
 *
 * Layout: activity_gst_reports.xml
 *
 * Sections:
 *  1. Header bar — FY, Period, Return-type selectors + Generate button
 *  2. Summary card — invoice count, taxable value, tax, credit notes
 *  3. Validation banner — errors / warnings count
 *  4. Tab strip + ViewPager — one tab per GSTR-1 section (13 sections)
 *  5. Action buttons — Validate, Save Draft, Export CSV, Export Excel
 *  6. Drafts section — list of previously saved drafts
 */
class GstReportsActivity : AppCompatActivity() {

    
    private val viewModel1: Gstr1ViewModel by viewModels()
    private val viewModel2: Gstr2ViewModel by viewModels()
    private var isGstr1 = true
    private lateinit var selectorReturnType: View
    private lateinit var tvSelectorValue: TextView
    private lateinit var tabAdapter2: Gstr2SheetTabAdapter


    // ── Header selectors ──────────────────────────────────────────────────────
    private lateinit var btnFyPrev: View
    private lateinit var btnFyNext: View
    private lateinit var tvFyValue: TextView
    private lateinit var tvFyRange: TextView
    private lateinit var llPeriodStrip: android.widget.LinearLayout
    private lateinit var segMonthly: TextView
    private lateinit var segQuarterly: TextView
    private lateinit var btnGenerate: MaterialButton
    private lateinit var progressGenerate: CircularProgressIndicator

    private var fyIndex = 0
    private var periodIndex = 0
    private var isMonthly = true

    // Per-month pill colours (lightFill, stroke, ink) — cycled by position.
    private val periodPalette = listOf(
        Triple("#EEEDFE", "#7F77DD", "#3C3489"), // purple
        Triple("#E1F5EE", "#1D9E75", "#0F6E56"), // teal
        Triple("#FAECE7", "#D85A30", "#993C1D"), // coral
        Triple("#FBEAF0", "#D4537E", "#72243E"), // pink
        Triple("#FAEEDA", "#BA7517", "#854F0B"), // amber
        Triple("#E6F1FB", "#378ADD", "#0C447C")  // blue
    )

    // ── GSTIN display ─────────────────────────────────────────────────────────
    private lateinit var tvGstin: TextView

    // ── Summary card ──────────────────────────────────────────────────────────
    private lateinit var cardSummary: View
    private lateinit var tvSummaryHeroLabel: TextView
    private lateinit var tvSummaryInvoices: TextView
    private lateinit var tvSummaryTaxable: TextView
    private lateinit var tvSummaryTax: TextView
    private lateinit var tvSummaryCreditNotes: TextView

    // ── Validation banner ─────────────────────────────────────────────────────
    private lateinit var llValidationBanner: LinearLayout
    private lateinit var tvValidationStatus: TextView

    // ── Section chips + pages ─────────────────────────────────────────────────
    private lateinit var scrollSections: View
    private lateinit var chipGroupSections: android.widget.LinearLayout
    private lateinit var cardSection: View
    private lateinit var viewPager: ViewPager2
    private lateinit var tabAdapter: Gstr1SheetTabAdapter

    // ── Action buttons ────────────────────────────────────────────────────────
    private lateinit var btnValidate: View
    private lateinit var btnSaveDraft: View
    private lateinit var btnExportCsv: View
    private lateinit var btnExportExcel: View

    // ── Drafts ────────────────────────────────────────────────────────────────
    private lateinit var llDraftsSection: LinearLayout
    private lateinit var rvDrafts: RecyclerView

    // ─────────────────────────────────────────────────────────────────────────

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
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gst_reports)
        com.example.easy_billing.util.UserEventLogger.logAction("GstReports", "opened")

        bindViews()
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setupSelectors()

        selectorReturnType.setOnClickListener { showReturnTypeDropdown() }
        tvSelectorValue.text = getString(R.string.gst_selector_gstr1_sales)

        setupTabs()
        setupButtons()
        observeViewModel()

        // Keep the section chips in sync when pages change (registered once;
        // chip children map 1:1 with pages, so this survives chip rebuilds).
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position < chipGroupSections.childCount) selectSection(position)
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

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

    // ─────────────────────────────────────────────────────────────────────────
    //  Report-type card selector
    // ─────────────────────────────────────────────────────────────────────────

    private fun dpPx(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Return-type picker — styled dropdown matching the Invoice field popups. */
    private fun showReturnTypeDropdown() {
        val box = selectorReturnType
        val options = listOf(getString(R.string.gst_selector_gstr1_sales), getString(R.string.gst_selector_gstr2_purchases))
        val currentIdx = if (isGstr1) 0 else 1

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dpPx(5), dpPx(5), dpPx(5), dpPx(5))
        }

        val popup = android.widget.PopupWindow(
            container, box.width.coerceAtLeast(dpPx(210)),
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, true
        ).apply {
            elevation = dpPx(10).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }

        val font = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.googlesans_medium)
        options.forEachIndexed { idx, opt ->
            val isSel = idx == currentIdx
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpPx(46)
                )
                setPadding(dpPx(12), 0, dpPx(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            row.addView(TextView(this).apply {
                text = opt
                textSize = 14f
                typeface = font
                setTextColor(Color.parseColor(if (isSel) "#0F6E56" else "#1A1A18"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            if (isSel) {
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(Color.parseColor("#0F6E56"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(dpPx(16), dpPx(16))
                })
            }
            row.setOnClickListener {
                switchReport(idx == 0)
                popup.dismiss()
            }
            container.addView(row)
        }

        popup.showAsDropDown(box, 0, dpPx(6))
    }

    /** Switch the visible return and reset the screen. */
    private fun switchReport(toGstr1: Boolean) {
        isGstr1 = toGstr1
        tvSelectorValue.text = if (isGstr1) getString(R.string.gst_selector_gstr1_sales) else getString(R.string.gst_selector_gstr2_purchases)

        setupTabs() // recreate section chips + pages for the chosen return

        // Reset FY stepper + period strip for the new return (current FY / month),
        // keeping the Monthly/Quarterly choice in sync with the target ViewModel.
        if (isGstr1) viewModel1.setReturnType(if (isMonthly) "Monthly" else "Quarterly")
        else viewModel2.setReturnType(if (isMonthly) "Monthly" else "Quarterly")
        fyIndex = defaultFyIndex()
        renderFy()
        periodIndex = defaultPeriodIndex()
        buildPeriodStrip()

        // Reset UI
        cardSummary.visibility = View.GONE
        llValidationBanner.visibility = View.GONE
        scrollSections.visibility = View.GONE
        cardSection.visibility = View.GONE
        viewPager.visibility = View.GONE
        llDraftsSection.visibility = View.GONE
        setActionButtonsEnabled(false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  View binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {

        selectorReturnType      = findViewById(R.id.selectorReturnType)
        tvSelectorValue         = findViewById(R.id.tvSelectorValue)

        btnFyPrev               = findViewById(R.id.btnFyPrev)
        btnFyNext               = findViewById(R.id.btnFyNext)
        tvFyValue               = findViewById(R.id.tvFyValue)
        tvFyRange               = findViewById(R.id.tvFyRange)
        llPeriodStrip           = findViewById(R.id.llPeriodStrip)
        segMonthly              = findViewById(R.id.segMonthly)
        segQuarterly            = findViewById(R.id.segQuarterly)
        btnGenerate             = findViewById(R.id.btnGenerate)
        progressGenerate        = findViewById(R.id.progressGenerate)
        tvGstin                 = findViewById(R.id.tvGstin)
        cardSummary             = findViewById(R.id.cardSummary)
        tvSummaryHeroLabel      = findViewById(R.id.tvSummaryHeroLabel)
        tvSummaryInvoices       = findViewById(R.id.tvSummaryInvoices)
        tvSummaryTaxable        = findViewById(R.id.tvSummaryTaxable)
        tvSummaryTax            = findViewById(R.id.tvSummaryTax)
        tvSummaryCreditNotes    = findViewById(R.id.tvSummaryCreditNotes)
        llValidationBanner      = findViewById(R.id.llValidationBanner)
        tvValidationStatus      = findViewById(R.id.tvValidationStatus)
        scrollSections          = findViewById(R.id.scrollSections)
        chipGroupSections       = findViewById(R.id.chipGroupSections)
        cardSection             = findViewById(R.id.cardSection)
        viewPager               = findViewById(R.id.viewPager)
        btnValidate             = findViewById(R.id.btnValidate)
        btnSaveDraft            = findViewById(R.id.btnSaveDraft)
        btnExportCsv            = findViewById(R.id.btnExportCsv)
        btnExportExcel          = findViewById(R.id.btnExportExcel)
        llDraftsSection         = findViewById(R.id.llDraftsSection)
        rvDrafts                = findViewById(R.id.rvDrafts)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Selectors setup
    // ─────────────────────────────────────────────────────────────────────────


    private fun currentFys() = if (isGstr1) viewModel1.availableFYs else viewModel2.availableFYs
    private fun currentPeriods() = if (isGstr1) viewModel1.availablePeriods else viewModel2.availablePeriods

    private fun setupSelectors() {
        // FY stepper — list is newest-first, so ‹ steps to older, › to newer.
        // Default to the current financial year.
        fyIndex = defaultFyIndex()
        renderFy()
        btnFyPrev.setOnClickListener { if (fyIndex < currentFys().size - 1) { fyIndex++; renderFy() } }
        btnFyNext.setOnClickListener { if (fyIndex > 0) { fyIndex--; renderFy() } }

        // Period strip — default to the current month / quarter.
        periodIndex = defaultPeriodIndex()
        buildPeriodStrip()

        // Monthly / Quarterly segmented toggle
        paintReturnType()
        segMonthly.setOnClickListener { setReturnMode(true) }
        segQuarterly.setOnClickListener { setReturnMode(false) }
    }

    private fun setReturnMode(monthly: Boolean) {
        if (monthly == isMonthly) return
        isMonthly = monthly
        paintReturnType()
        if (isGstr1) viewModel1.setReturnType(if (monthly) "Monthly" else "Quarterly")
        else viewModel2.setReturnType(if (monthly) "Monthly" else "Quarterly")
        periodIndex = defaultPeriodIndex()
        buildPeriodStrip()
    }

    /** Paint the Monthly / Quarterly segments — active is the dark pill. */
    private fun paintReturnType() {
        val active = if (isMonthly) segMonthly else segQuarterly
        val idle = if (isMonthly) segQuarterly else segMonthly
        active.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1A1A18"))
        active.setTextColor(Color.parseColor("#FFFFFF"))
        idle.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        idle.setTextColor(Color.parseColor("#8A7F68"))
    }

    /** Index of the current financial year within the available list. */
    private fun defaultFyIndex(): Int {
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH) + 1        // 1-based
        val year = cal.get(java.util.Calendar.YEAR)
        val fyStart = if (month >= 4) year else year - 1
        val currentFy = "$fyStart-${(fyStart + 1).toString().takeLast(2)}"
        return currentFys().indexOf(currentFy).coerceAtLeast(0)
    }

    /** Index of the current month (monthly) or current quarter (quarterly). */
    private fun defaultPeriodIndex(): Int {
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val fiscalIdx = if (month >= 4) month - 4 else month + 8  // Apr = 0 … Mar = 11
        val size = currentPeriods().size
        if (size == 0) return 0
        val idx = if (isMonthly) fiscalIdx else fiscalIdx / 3
        return idx.coerceIn(0, size - 1)
    }

    /** Paint the FY value + date range and push the selection to the ViewModel. */
    private fun renderFy() {
        val fys = currentFys()
        if (fys.isEmpty()) return
        fyIndex = fyIndex.coerceIn(0, fys.size - 1)
        val fy = fys[fyIndex]                       // raw "2025-26" for the ViewModel
        tvFyValue.text = fy.replace("-", "–")       // en-dash for display only
        val start = fy.substringBefore("-").toIntOrNull()
        tvFyRange.text = if (start != null) "Apr $start – Mar ${start + 1}" else ""
        if (isGstr1) viewModel1.setFinancialYear(fy) else viewModel2.setFinancialYear(fy)
    }

    /** Rebuild the month / quarter pill strip for the current return type. */
    private fun buildPeriodStrip() {
        llPeriodStrip.removeAllViews()
        val periods = currentPeriods()
        if (periods.isEmpty()) return
        periodIndex = periodIndex.coerceIn(0, periods.size - 1)
        val d = resources.displayMetrics.density
        val font = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.googlesans_medium)
        periods.forEachIndexed { index, label ->
            val pill = TextView(this).apply {
                text = label
                textSize = 12f
                includeFontPadding = false
                typeface = font
                setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * d).toInt() }
                setOnClickListener { selectPeriod(index) }
            }
            llPeriodStrip.addView(pill)
        }
        selectPeriod(periodIndex)
    }

    private fun selectPeriod(index: Int) {
        periodIndex = index
        // Same hashed-accent-per-chip concept as the dashboard's category rail
        // and Profit analytics' date chips — white pill always, coloured
        // 1.5dp stroke + text only when selected, instead of a filled tint.
        val d = resources.displayMetrics.density
        for (i in 0 until llPeriodStrip.childCount) {
            val pill = llPeriodStrip.getChildAt(i) as TextView
            val accent = Color.parseColor(periodPalette[i % periodPalette.size].second)
            val strokeColor = if (i == index) accent else Color.parseColor("#E4DCC8")
            val textColor = if (i == index) accent else Color.parseColor("#6E6A60")
            pill.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8f * d
                setColor(Color.parseColor("#FFFFFF"))
                setStroke((1.5f * d).toInt(), strokeColor)
            }
            pill.setTextColor(textColor)
        }
        val periods = currentPeriods()
        if (periods.isNotEmpty()) {
            if (isGstr1) viewModel1.setPeriod(periods[index]) else viewModel2.setPeriod(periods[index])
        }

        // Bring the selected pill into view (default may sit mid-strip).
        val selected = llPeriodStrip.getChildAt(index) ?: return
        (llPeriodStrip.parent as? android.widget.HorizontalScrollView)?.post {
            (llPeriodStrip.parent as? android.widget.HorizontalScrollView)
                ?.smoothScrollTo((selected.left - 40).coerceAtLeast(0), 0)
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Tabs
    // ─────────────────────────────────────────────────────────────────────────

    
    private fun setupTabs() {
        if (isGstr1) {
            tabAdapter = Gstr1SheetTabAdapter(this)
            viewPager.adapter = tabAdapter
            buildSectionChips(Gstr1SheetTabAdapter.TAB_LABELS.toList())
        } else {
            tabAdapter2 = Gstr2SheetTabAdapter(this)
            viewPager.adapter = tabAdapter2
            buildSectionChips(Gstr2SheetTabAdapter.TAB_LABELS.toList())
        }
    }

    /** Build the champagne section chips that drive the ViewPager. Selected chip
     *  is the green pill with a gold count badge. Chip index == page index. */
    private fun buildSectionChips(labels: List<String>) {
        chipGroupSections.removeAllViews()
        val d = resources.displayMetrics.density
        val font = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.googlesans_medium)
        val counts = sectionCounts()

        labels.forEachIndexed { index, label ->
            val chip = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((13 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * d).toInt() }
                isClickable = true
                setOnClickListener { viewPager.setCurrentItem(index, false); selectSection(index) }
            }
            chip.addView(TextView(this).apply {
                text = label
                textSize = 12.5f
                typeface = font
                includeFontPadding = false
            })
            chip.addView(TextView(this).apply {
                text = counts.getOrNull(index)?.toString() ?: "0"
                textSize = 11f
                typeface = font
                includeFontPadding = false
                gravity = android.view.Gravity.CENTER
                minWidth = (22 * d).toInt()
                setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (7 * d).toInt() }
            })
            chipGroupSections.addView(chip)
        }
        selectSection(0)
    }

    /** Row count per section, in the same order as the tab labels. */
    private fun sectionCounts(): List<Int> {
        if (isGstr1) {
            val r = viewModel1.report.value ?: return List(13) { 0 }
            return listOf(
                r.b2b.size, r.b2cl.size, r.b2cs.size, r.cdnr.size, r.cdnur.size,
                r.hsnB2B.size, r.hsnB2C.size, r.docs.size,
                r.eco.size, r.ecoB2B.size, r.ecoB2C.size, r.ecoUrp2B.size, r.ecoUrp2C.size
            )
        }
        val r = viewModel2.report.value ?: return List(8) { 0 }
        return listOf(
            r.b2b.size, r.b2bur.size, r.imps.size, r.impg.size,
            r.cdnr.size, r.cdnur.size, r.exemp.size, r.hsnsum.size
        )
    }

    /** Paint the section chips — active is the green pill with a gold count badge. */
    private fun selectSection(index: Int) {
        val d = resources.displayMetrics.density
        for (i in 0 until chipGroupSections.childCount) {
            val chip = chipGroupSections.getChildAt(i) as android.widget.LinearLayout
            val label = chip.getChildAt(0) as TextView
            val badge = chip.getChildAt(1) as TextView
            val selected = i == index

            chip.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 9f * d
                setColor(Color.parseColor(if (selected) "#0F6E56" else "#F1EFE8"))
            }
            label.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#6E6A60"))

            badge.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8f * d
                setColor(Color.parseColor(if (selected) "#E8B04B" else "#E4DCC8"))
            }
            badge.setTextColor(Color.parseColor(if (selected) "#1A1A18" else "#6E6A60"))
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Buttons
    // ─────────────────────────────────────────────────────────────────────────

    
    private fun setupButtons() {
        btnGenerate.setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("GstReports", "generate_clicked: report=${if (isGstr1) "GSTR1" else "GSTR2"}")
            if (isGstr1) viewModel1.generateReport() else viewModel2.generateReport()
        }
        btnValidate.setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("GstReports", "validate_clicked: report=${if (isGstr1) "GSTR1" else "GSTR2"}")
            if (isGstr1) {
                if (viewModel1.report.value == null) {
                    // validateReport() no-ops when no report exists yet — without
                    // this the button feels dead when tapped too early.
                    Toast.makeText(this, getString(R.string.gst_toast_generate_first_validate), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // validationResult is a StateFlow of a data class — if the report
                // hasn't changed since the last check, the recomputed result is
                // equal to the stored one and StateFlow drops the emission, so
                // collectLatest() never re-fires and the banner looks frozen.
                // Bind directly here so every tap gives visible feedback, and
                // toast the outcome too in case the banner is scrolled off-screen.
                viewModel1.validateReport()
                val result = viewModel1.validationResult.value
                if (result != null) {
                    bindValidation1(result)
                    showValidationDialog(result)
                }
            } else {
                Toast.makeText(this, getString(R.string.gst_toast_validation_not_available_gstr2), Toast.LENGTH_SHORT).show()
            }
        }
        btnSaveDraft.setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("GstReports", "save_draft_clicked: report=${if (isGstr1) "GSTR1" else "GSTR2"}")
            if (isGstr1) viewModel1.saveDraft() else viewModel2.saveDraft()
        }
        btnExportCsv.setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("GstReports", "export_csv_clicked: report=${if (isGstr1) "GSTR1" else "GSTR2"}")
            if (isGstr1) viewModel1.exportCsv() else viewModel2.exportCsv()
        }
        btnExportExcel.setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("GstReports", "export_excel_clicked: report=${if (isGstr1) "GSTR1" else "GSTR2"}")
            if (isGstr1) viewModel1.exportExcel() else viewModel2.exportExcel()
        }
    }


    private fun setActionButtonsEnabled(enabled: Boolean) {
        for (v in listOf<View>(btnValidate, btnExportExcel, btnExportCsv, btnSaveDraft)) {
            v.isEnabled = enabled
            v.alpha = if (enabled) 1f else 0.45f
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ViewModel observation
    // ─────────────────────────────────────────────────────────────────────────

    
    private fun observeViewModel() {
        // GSTR-1
        lifecycleScope.launch {
            viewModel1.gstin.collectLatest { gstin ->
                if (isGstr1) tvGstin.text = if (gstin.isNotBlank()) gstin else "Not configured"
            }
        }
        lifecycleScope.launch {
            viewModel1.isLoading.collectLatest { loading ->
                if (isGstr1) handleLoading(loading)
            }
        }
        lifecycleScope.launch { viewModel1.report.collectLatest { if (isGstr1 && it != null) bindReport1(it) } }
        lifecycleScope.launch { viewModel1.validationResult.collectLatest { if (isGstr1 && it != null) bindValidation1(it) } }
        lifecycleScope.launch { viewModel1.error.collectLatest { if (isGstr1 && it != null) { Toast.makeText(this@GstReportsActivity, it, Toast.LENGTH_LONG).show(); viewModel1.clearError() } } }
        lifecycleScope.launch { viewModel1.exportEvent.collectLatest { if (isGstr1 && it != null) { handleExportEvent1(it); viewModel1.clearExportEvent() } } }
        lifecycleScope.launch { viewModel1.drafts.collectLatest { if (isGstr1) bindDrafts1(it) } }

        // GSTR-2
        lifecycleScope.launch {
            viewModel2.gstin.collectLatest { gstin ->
                if (!isGstr1) tvGstin.text = if (gstin.isNotBlank()) gstin else "Not configured"
            }
        }
        lifecycleScope.launch {
            viewModel2.isLoading.collectLatest { loading ->
                if (!isGstr1) handleLoading(loading)
            }
        }
        lifecycleScope.launch { viewModel2.report.collectLatest { if (!isGstr1 && it != null) bindReport2(it) } }
        lifecycleScope.launch { viewModel2.error.collectLatest { if (!isGstr1 && it != null) { Toast.makeText(this@GstReportsActivity, it, Toast.LENGTH_LONG).show(); viewModel2.clearError() } } }
        lifecycleScope.launch { viewModel2.exportEvent.collectLatest { if (!isGstr1 && it != null) { handleExportEvent2(it); viewModel2.clearExportEvent() } } }
        lifecycleScope.launch { viewModel2.drafts.collectLatest { if (!isGstr1) bindDrafts2(it) } }
    }

    private fun handleLoading(loading: Boolean) {
        progressGenerate.visibility = if (loading) View.VISIBLE else View.GONE
        btnGenerate.isEnabled = !loading
        btnGenerate.text = if (loading) "Generating…" else "Generate report"
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Report binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindReport1(report: Gstr1Report) {
        // Summary hero
        cardSummary.visibility = View.VISIBLE
        tvSummaryHeroLabel.text   = getString(R.string.gst_hero_label_total_tax)
        tvSummaryTax.text         = "${CurrencyHelper.getCurrencySymbol(this)}%,.0f".format(report.totalTax)
        tvSummaryTaxable.text     = getString(R.string.gst_summary_taxable_suffix).format(report.totalTaxable)
        tvSummaryInvoices.text    = report.totalInvoiceCount.toString()
        tvSummaryCreditNotes.text = report.totalCreditNotes.toString()

        // Section chips + pages (rebuild so the count badges pick up this report)
        buildSectionChips(Gstr1SheetTabAdapter.TAB_LABELS.toList())
        scrollSections.visibility = View.VISIBLE
        cardSection.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE

        setActionButtonsEnabled(true)
    }

    private fun bindValidation1(result: Gstr1Validator.ValidationResult) {
        llValidationBanner.visibility = View.VISIBLE

        // The banner background is a rounded pill drawable; tint it (keeps the
        // corners) rather than setBackgroundColor (which would flatten them),
        // and match the text colour to the champagne tile palette.
        fun paint(bg: String, fg: String) {
            llValidationBanner.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bg))
            tvValidationStatus.setTextColor(Color.parseColor(fg))
        }

        when {
            result.hasErrors -> {
                tvValidationStatus.text =
                    "⚠ ${result.errorCount} error(s), ${result.warningCount} warning(s) — Fix errors before filing"
                paint("#FBEDED", "#A32D2D")
            }
            result.hasWarnings -> {
                tvValidationStatus.text =
                    "⚡ ${result.warningCount} warning(s) — Review before filing"
                paint("#FAEEDA", "#8A6526")
            }
            else -> {
                tvValidationStatus.text = getString(R.string.gst_validation_all_checks_passed)
                paint("#E1F5EE", "#0F5943")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Drafts
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindDrafts1(drafts: List<Gstr1DraftEntity>) {
        llDraftsSection.visibility = if (drafts.isEmpty()) View.GONE else View.VISIBLE
        rvDrafts.layoutManager = LinearLayoutManager(this)
        rvDrafts.adapter = Gstr1DraftsAdapter(drafts,
            onOpen   = { viewModel1.loadDraftById(it.id) },
            onDelete = { confirmDeleteDraft(it) }
        )
    }

    private fun confirmDeleteDraft(draft: Gstr1DraftEntity) {
        showGstConfirmDialog(
            eyebrow = getString(R.string.gst_delete_draft_eyebrow),
            titleBold = getString(R.string.gst_delete_draft_title_bold),
            titleAccent = getString(R.string.gst_delete_draft_title_accent),
            message = "GSTR-1 draft for ${draft.period} ${draft.financialYear} will be permanently removed.",
            positiveLabel = getString(R.string.gst_delete_draft_positive_label)
        ) {
            com.example.easy_billing.util.UserEventLogger.logAction(
                "GstReports",
                "delete_gstr1_draft_clicked: period=${draft.period}, financial_year=${draft.financialYear}, id=${draft.id}"
            )
            viewModel1.deleteDraft(draft.id)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Champagne-themed dialog helpers, reused for every GST confirm/export popup
    // ─────────────────────────────────────────────────────────────────────────

    private fun showGstConfirmDialog(
        eyebrow: String,
        titleBold: String,
        titleAccent: String,
        message: String,
        positiveLabel: String = "Delete",
        onPositive: () -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_gst_confirm, null)
        view.findViewById<TextView>(R.id.tvEyebrow).text = eyebrow
        view.findViewById<TextView>(R.id.tvTitleBold).text = titleBold
        view.findViewById<TextView>(R.id.tvTitleAccent).text = titleAccent
        view.findViewById<TextView>(R.id.tvMessage).text = message
        view.findViewById<MaterialButton>(R.id.btnPositive).text = positiveLabel

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<MaterialButton>(R.id.btnNegative).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnPositive).setOnClickListener {
            dialog.dismiss()
            onPositive()
        }
        dialog.show()
    }

    private fun showGstSuccessDialog(
        eyebrow: String,
        titleBold: String,
        titleAccent: String,
        message: String,
        infoLabel: String? = null,
        infoValue: String? = null,
        primaryLabel: String,
        onPrimary: () -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_gst_success, null)
        view.findViewById<TextView>(R.id.tvEyebrow).text = eyebrow
        view.findViewById<TextView>(R.id.tvTitleBold).text = titleBold
        view.findViewById<TextView>(R.id.tvTitleAccent).text = titleAccent
        view.findViewById<TextView>(R.id.tvMessage).text = message

        if (infoValue != null) {
            view.findViewById<LinearLayout>(R.id.cardInfo).visibility = View.VISIBLE
            infoLabel?.let { view.findViewById<TextView>(R.id.tvInfoLabel).text = it }
            view.findViewById<TextView>(R.id.tvInfoValue).text = infoValue
        }

        view.findViewById<MaterialButton>(R.id.btnPrimary).text = primaryLabel

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<MaterialButton>(R.id.btnSecondary).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnPrimary).setOnClickListener {
            dialog.dismiss()
            onPrimary()
        }
        dialog.show()
    }

    private fun showValidationDialog(result: Gstr1Validator.ValidationResult) {
        val view = layoutInflater.inflate(R.layout.dialog_gst_validation, null)

        val badgeFrame  = view.findViewById<FrameLayout>(R.id.badgeFrame)
        val ivBadge     = view.findViewById<ImageView>(R.id.ivBadge)
        val tvTitleBold = view.findViewById<TextView>(R.id.tvTitleBold)
        val tvTitleAcc  = view.findViewById<TextView>(R.id.tvTitleAccent)
        val tvMessage   = view.findViewById<TextView>(R.id.tvMessage)
        val cardIssues  = view.findViewById<LinearLayout>(R.id.cardIssues)
        val scrollIssues = view.findViewById<View>(R.id.scrollIssues)
        val llIssuesList = view.findViewById<LinearLayout>(R.id.llIssuesList)

        when {
            result.hasErrors -> {
                badgeFrame.background = ContextCompat.getDrawable(this, R.drawable.bg_circle_soft_red)
                ivBadge.setImageResource(R.drawable.ic_lucide_circle_x)
                ivBadge.imageTintList = ColorStateList.valueOf(Color.parseColor("#A32D2D"))
                tvTitleBold.text = getString(R.string.gst_validation_title_report)
                tvTitleAcc.text = getString(R.string.gst_validation_title_issues)
                tvMessage.text = "${result.errorCount} error(s), ${result.warningCount} warning(s) — fix errors before filing"
            }
            result.hasWarnings -> {
                badgeFrame.background = ContextCompat.getDrawable(this, R.drawable.bg_circle_soft_gold)
                ivBadge.setImageResource(R.drawable.ic_lc_alert_triangle)
                ivBadge.imageTintList = ColorStateList.valueOf(Color.parseColor("#8A6526"))
                tvTitleBold.text = getString(R.string.gst_validation_title_review)
                tvTitleAcc.text = getString(R.string.gst_validation_title_warnings)
                tvMessage.text = "${result.warningCount} warning(s) — worth a look before filing"
            }
            else -> {
                badgeFrame.background = ContextCompat.getDrawable(this, R.drawable.bg_circle_soft_teal)
                ivBadge.setImageResource(R.drawable.ic_lc_circle_check)
                ivBadge.imageTintList = ColorStateList.valueOf(Color.parseColor("#085041"))
                tvTitleBold.text = getString(R.string.gst_validation_title_all)
                tvTitleAcc.text = getString(R.string.gst_validation_title_clear)
                tvMessage.text = getString(R.string.gst_validation_message_no_issues)
            }
        }

        if (result.issues.isNotEmpty()) {
            cardIssues.visibility = View.VISIBLE
            llIssuesList.removeAllViews()

            result.issues.forEachIndexed { index, issue ->
                val row = layoutInflater.inflate(R.layout.item_validation_issue, llIssuesList, false)
                val tileBg = row.findViewById<FrameLayout>(R.id.ivTileBg)
                val icon   = row.findViewById<ImageView>(R.id.ivIcon)
                row.findViewById<TextView>(R.id.tvSection).text = issue.section
                row.findViewById<TextView>(R.id.tvMessage).text =
                    if (issue.rowHint.isNotBlank()) "${issue.message} (${issue.rowHint})" else issue.message

                if (issue.severity == Gstr1Validator.Severity.ERROR) {
                    tileBg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FCEBEB"))
                    icon.setImageResource(R.drawable.ic_lucide_circle_x)
                    icon.imageTintList = ColorStateList.valueOf(Color.parseColor("#A32D2D"))
                } else {
                    tileBg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FAEEDA"))
                    icon.setImageResource(R.drawable.ic_lc_alert_triangle)
                    icon.imageTintList = ColorStateList.valueOf(Color.parseColor("#8A6526"))
                }

                llIssuesList.addView(row)

                if (index != result.issues.lastIndex) {
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(Color.parseColor("#F0EBDD"))
                    }
                    llIssuesList.addView(divider)
                }
            }

            // Cap the scroll area's height once there are enough rows to need
            // scrolling, so the dialog doesn't grow past the screen.
            if (result.issues.size > 4) {
                scrollIssues.layoutParams.height = (220 * resources.displayMetrics.density).toInt()
                scrollIssues.requestLayout()
            }
        } else {
            cardIssues.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Export events
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleExportEvent1(event: Gstr1ViewModel.ExportEvent) {
        when (event) {
            is Gstr1ViewModel.ExportEvent.DraftSaved -> {
                Toast.makeText(this, getString(R.string.gst_toast_draft_saved), Toast.LENGTH_SHORT).show()
            }
            is Gstr1ViewModel.ExportEvent.CsvExported -> {
                showGstSuccessDialog(
                    eyebrow = getString(R.string.gst_csv_export_eyebrow),
                    titleBold = getString(R.string.gst_export_title_bold),
                    titleAccent = getString(R.string.gst_export_title_accent),
                    message = "${event.files.size} CSV file(s) ready · ${event.files.keys.joinToString(", ")}",
                    infoLabel = getString(R.string.gst_export_info_label_saved_to),
                    infoValue = event.directory,
                    primaryLabel = getString(R.string.gst_export_primary_label_share_all)
                ) {
                    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "text/csv"
                        val uriList = ArrayList(event.files.values)
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.gst_share_gstr1_csvs_chooser)))
                }
            }
            is Gstr1ViewModel.ExportEvent.ExcelExported -> {
                showGstSuccessDialog(
                    eyebrow = getString(R.string.gst_excel_export_eyebrow),
                    titleBold = getString(R.string.gst_export_title_bold),
                    titleAccent = getString(R.string.gst_export_title_accent),
                    message = getString(R.string.gst_excel_gstr1_message),
                    infoLabel = getString(R.string.gst_export_info_label_saved_to),
                    infoValue = event.path,
                    primaryLabel = getString(R.string.gst_export_primary_label_open)
                ) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(event.uri,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        com.example.easy_billing.util.UserEventLogger.logError(
                            "GstReports", "no_xlsx_viewer_app: ${e.javaClass.simpleName}"
                        )
                        Toast.makeText(this, getString(R.string.gst_toast_no_xlsx_app), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }


    private fun bindReport2(report: Gstr2Report) {
        cardSummary.visibility = View.VISIBLE
        tvSummaryHeroLabel.text   = getString(R.string.gst_hero_label_itc)
        tvSummaryTax.text         = "${CurrencyHelper.getCurrencySymbol(this)}%,.0f".format(report.totalTax)
        tvSummaryTaxable.text     = getString(R.string.gst_summary_taxable_suffix).format(report.totalTaxable)
        tvSummaryInvoices.text    = report.totalInvoiceCount.toString()
        tvSummaryCreditNotes.text = report.totalCreditNotes.toString()

        // Section chips + pages (rebuild so the count badges pick up this report)
        buildSectionChips(Gstr2SheetTabAdapter.TAB_LABELS.toList())
        scrollSections.visibility = View.VISIBLE
        cardSection.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE

        setActionButtonsEnabled(true)
    }

    private fun bindDrafts2(drafts: List<Gstr2DraftEntity>) {
        llDraftsSection.visibility = if (drafts.isEmpty()) View.GONE else View.VISIBLE
        rvDrafts.layoutManager = LinearLayoutManager(this)
        rvDrafts.adapter = Gstr2DraftsAdapter(drafts,
            onOpen   = { viewModel2.loadDraft(it) },
            onDelete = { draftId ->
                showGstConfirmDialog(
                    eyebrow = getString(R.string.gst_delete_draft_eyebrow),
                    titleBold = getString(R.string.gst_delete_draft_title_bold),
                    titleAccent = getString(R.string.gst_delete_draft_title_accent),
                    message = getString(R.string.gst_delete_draft_gstr2_message),
                    positiveLabel = getString(R.string.gst_delete_draft_positive_label)
                ) {
                    com.example.easy_billing.util.UserEventLogger.logAction(
                        "GstReports", "delete_gstr2_draft_clicked: id=$draftId"
                    )
                    viewModel2.deleteDraft(draftId)
                }
            }
        )
    }

    private fun handleExportEvent2(event: Gstr2ViewModel.ExportEvent) {
        when (event) {
            is Gstr2ViewModel.ExportEvent.DraftSaved -> Toast.makeText(this, getString(R.string.gst_toast_draft_saved), Toast.LENGTH_SHORT).show()
            is Gstr2ViewModel.ExportEvent.CsvExported -> {
                showGstSuccessDialog(
                    eyebrow = getString(R.string.gst_csv_export_eyebrow),
                    titleBold = getString(R.string.gst_export_title_bold),
                    titleAccent = getString(R.string.gst_export_title_accent),
                    message = "${event.files.size} CSV file(s) are ready.",
                    infoLabel = getString(R.string.gst_export_info_label_saved_to),
                    infoValue = event.directory,
                    primaryLabel = getString(R.string.gst_export_primary_label_share_all)
                ) {
                    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "text/csv"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(event.files.values))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.gst_share_gstr2_csvs_chooser)))
                }
            }
            is Gstr2ViewModel.ExportEvent.ExcelExported -> {
                showGstSuccessDialog(
                    eyebrow = getString(R.string.gst_excel_export_eyebrow),
                    titleBold = getString(R.string.gst_export_title_bold),
                    titleAccent = getString(R.string.gst_export_title_accent),
                    message = getString(R.string.gst_excel_gstr2_message),
                    infoLabel = getString(R.string.gst_export_info_label_saved_to),
                    infoValue = event.path,
                    primaryLabel = getString(R.string.gst_export_primary_label_open)
                ) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(event.uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try { startActivity(intent) } catch (e: Exception) {
                        com.example.easy_billing.util.UserEventLogger.logError(
                            "GstReports", "no_viewer_app: ${e.javaClass.simpleName}"
                        )
                        Toast.makeText(this, getString(R.string.gst_toast_no_app_found), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
