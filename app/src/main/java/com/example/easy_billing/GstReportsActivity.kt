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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        // A reminder notification can open this page after the session ended — go to login instead
        // of showing a load error.
        val authToken = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", "") ?: ""
        if (authToken.isBlank()) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            return
        }
        savedState = savedInstanceState
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
        setupSimpleFlow()

        // Keep the section chips in sync when pages change (registered once;
        // chip children map 1:1 with pages, so this survives chip rebuilds).
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position < chipGroupSections.childCount) selectSection(position)
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private var savedState: Bundle? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("gst_choice", simpleChoice)
        outState.putInt("gst_year", simpleYear)
        outState.putInt("gst_month", simpleMonth0)
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
        findViewById<View>(R.id.tvSheetsToggle).visibility = View.GONE
        findViewById<View>(R.id.tvSheetsHeading).visibility = View.GONE
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
        // Accountant section: keep tables and exports, hide the confusing extras
        for (hv in listOf<View?>(btnGenerate, btnValidate, btnSaveDraft, selectorReturnType, btnFyPrev, btnFyNext,
            findViewById<View>(R.id.segReturnType))) hv?.visibility = View.GONE
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
        active.setBackgroundResource(R.drawable.bg_lg_tab_active)
        active.backgroundTintList = null
        active.setTextColor(Color.parseColor("#FFFFFF"))
        idle.setBackgroundResource(R.drawable.bg_period_pill)
        idle.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        idle.setTextColor(Color.parseColor("#6B7770"))
    }

    /** Index of the current financial year within the available list. */
    private fun defaultFyIndex(): Int {
        val cal = com.example.easy_billing.util.AppTime.calendar()
        val month = cal.get(java.util.Calendar.MONTH) + 1        // 1-based
        val year = cal.get(java.util.Calendar.YEAR)
        val fyStart = if (month >= 4) year else year - 1
        val currentFy = "$fyStart-${(fyStart + 1).toString().takeLast(2)}"
        return currentFys().indexOf(currentFy).coerceAtLeast(0)
    }

    /** Index of the current month (monthly) or current quarter (quarterly). */
    private fun defaultPeriodIndex(): Int {
        val cal = com.example.easy_billing.util.AppTime.calendar()
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
            val strokeColor = if (i == index) accent else Color.parseColor("#D5DDD8")
            val textColor = if (i == index) accent else Color.parseColor("#5B6660")
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

    

    // ─────────────────────────────────────────────────────────────────────────
    //  Simple flow — pick a month, see plain numbers, send to accountant
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var llSimple: View
    private lateinit var llAdvanced: View
    private lateinit var chipGstThis: View
    private lateinit var chipGstLast: View
    private lateinit var chipGstOlder: View
    private lateinit var tvGstGstinWarn: TextView
    private lateinit var llGstComposition: View
    private lateinit var llGstLoading: View
    private lateinit var cardGstNumbers: View
    private lateinit var tvGstSelected: TextView
    private lateinit var tvGstSales: TextView
    private lateinit var tvGstBills: TextView
    private lateinit var tvGstCollected: TextView
    private lateinit var tvGstCredit: TextView
    private lateinit var tvGstToPay: TextView
    private lateinit var llGstChecks: View
    private lateinit var tvGstToPayTag: TextView
    private lateinit var boxGstTotal: View
    private lateinit var lblGstYouPay: TextView
    private lateinit var tvGstToPayNote: TextView
    private lateinit var tvGstStatus: TextView
    private lateinit var tvGstDue: TextView
    private lateinit var btnSendAccountant: MaterialButton
    private lateinit var tvGstDetailsToggle: TextView
    private lateinit var tvGstAsk: TextView
    private lateinit var tvGstFilingMode: TextView
    private lateinit var tvGstReminder: TextView
    private lateinit var tvGstFilingSub: TextView
    private lateinit var segGstMonthly: TextView
    private lateinit var segGstQuarterly: TextView
    private lateinit var swGstReminder: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var tvGstFiled: TextView
    private lateinit var rowGstFiled: View
    private lateinit var tvGstFiledSub: TextView
    private lateinit var swGstFiled: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var tvGstNotIncluded: TextView
    private var simpleQuarterly = false
    private val quarterNames = listOf("Apr-Jun", "Jul-Sep", "Oct-Dec", "Jan-Mar")

    private var simpleChoice = 1            // 0 = this month, 1 = last month, 2 = older
    private var simpleFy = ""
    private var simplePeriod = ""
    private var simpleYear = 0
    private var simpleMonth0 = 0            // 0 = January
    private var simpleMonthLabel = ""
    private var simpleError: String? = null
    private var sendingToAccountant = false
    private val fiscalMonthNames = listOf(
        "April", "May", "June", "July", "August", "September",
        "October", "November", "December", "January", "February", "March"
    )

    private fun setupSimpleFlow() {
        llSimple = findViewById(R.id.llSimple)
        llAdvanced = findViewById(R.id.llAdvanced)
        chipGstThis = findViewById(R.id.chipGstThis)
        chipGstLast = findViewById(R.id.chipGstLast)
        chipGstOlder = findViewById(R.id.chipGstOlder)
        tvGstGstinWarn = findViewById(R.id.tvGstGstinWarn)
        llGstComposition = findViewById(R.id.llGstComposition)
        findViewById<View>(R.id.btnGstCompTell).setOnClickListener {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.gst_comp_msg))
                }, getString(R.string.gst_comp_tell)))
        }
        llGstLoading = findViewById(R.id.llGstLoading)
        cardGstNumbers = findViewById(R.id.cardGstNumbers)
        tvGstSelected = findViewById(R.id.tvGstSelected)
        tvGstSales = findViewById(R.id.tvGstSales)
        tvGstBills = findViewById(R.id.tvGstBills)
        tvGstCollected = findViewById(R.id.tvGstCollected)
        tvGstCredit = findViewById(R.id.tvGstCredit)
        tvGstToPay = findViewById(R.id.tvGstToPay)
        llGstChecks = findViewById(R.id.llGstChecks)
        tvGstToPayTag = findViewById(R.id.tvGstToPayTag)
        boxGstTotal = findViewById(R.id.boxGstTotal)
        lblGstYouPay = findViewById(R.id.lblGstYouPay)
        tvGstToPayNote = findViewById(R.id.tvGstToPayNote)
        tvGstStatus = findViewById(R.id.tvGstStatus)
        tvGstDue = findViewById(R.id.tvGstDue)
        btnSendAccountant = findViewById(R.id.btnSendAccountant)
        tvGstDetailsToggle = findViewById(R.id.tvGstDetailsToggle)
        tvGstAsk = findViewById(R.id.tvGstAsk)
        tvGstFilingMode = findViewById(R.id.tvGstFilingMode)
        tvGstReminder = findViewById(R.id.tvGstReminder)
        tvGstFilingSub = findViewById(R.id.tvGstFilingSub)
        segGstMonthly = findViewById(R.id.segGstMonthly)
        segGstQuarterly = findViewById(R.id.segGstQuarterly)
        swGstReminder = findViewById(R.id.swGstReminder)
        tvGstFiled = findViewById(R.id.tvGstFiled)
        rowGstFiled = findViewById(R.id.rowGstFiled)
        tvGstFiledSub = findViewById(R.id.tvGstFiledSub)
        swGstFiled = findViewById(R.id.swGstFiled)
        tvGstNotIncluded = findViewById(R.id.tvGstNotIncluded)
        rowGstFiled.setOnClickListener {
            val prefs = getSharedPreferences("gst_prefs", MODE_PRIVATE)
            val key = com.example.easy_billing.gstr1.GstPeriod.filedKey(simpleFy, simplePeriod)
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, false)).apply()
            renderSimple()
        }
        // Composition shops file neither GSTR-1 nor GSTR-3B — show an explanation instead of numbers.
        lifecycleScope.launch {
            viewModel1.gstScheme.collectLatest { scheme ->
                val comp = scheme.contains("compos", ignoreCase = true)
                getSharedPreferences("gst_prefs", MODE_PRIVATE).edit().putBoolean("composition", comp).apply()
                renderSimple()
            }
        }

        val gp = getSharedPreferences("gst_prefs", MODE_PRIVATE)
        simpleQuarterly = gp.getBoolean("quarterly", false)
        gp.edit().putBoolean("opened", true).apply()   // reminders only for shops that use this page
        segGstMonthly.setOnClickListener { setFilingMode(false) }
        segGstQuarterly.setOnClickListener { setFilingMode(true) }
        findViewById<View>(R.id.barGstReminder).setOnClickListener {
            val on = !gp.getBoolean("reminders_on", true)
            gp.edit().putBoolean("reminders_on", on).apply()
            paintChips()
        }

        tvGstDetailsToggle.setOnClickListener {
            val show = llAdvanced.visibility != View.VISIBLE
            llAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            tvGstDetailsToggle.text = getString(if (show) R.string.gst_simple_details_hide else R.string.gst_simple_details_show)
        }
        chipGstThis.setOnClickListener { chooseSimpleMonth(0) }
        chipGstLast.setOnClickListener { chooseSimpleMonth(1) }
        chipGstOlder.setOnClickListener { showOlderMonths() }
        tvGstStatus.setOnClickListener {
            val r1 = simpleReport1()
            val v = viewModel1.validationResult.value
            if (r1 == null) loadSimpleMonth()
            else if (v != null && v.issues.isNotEmpty()) showValidationDialog(v)
        }
        btnSendAccountant.setOnClickListener { sendToAccountant() }
        llGstChecks.setOnClickListener {
            val v = viewModel1.validationResult.value
            if (v != null && v.issues.isNotEmpty()) showValidationDialog(v)
        }

        lifecycleScope.launch { viewModel1.report.collectLatest { renderSimple() } }
        lifecycleScope.launch { viewModel2.report.collectLatest { renderSimple() } }
        lifecycleScope.launch { viewModel1.isLoading.collectLatest { renderSimple() } }
        lifecycleScope.launch { viewModel2.isLoading.collectLatest { renderSimple() } }
        lifecycleScope.launch { viewModel1.validationResult.collectLatest { renderSimple() } }

        // Open straight onto last month — the one that is normally being filed.
        // After a rotation, keep the month the owner picked and reuse the reports already loaded.
        val st = savedState
        if (st != null && st.containsKey("gst_year")) {
            simpleChoice = st.getInt("gst_choice")
            simpleYear = st.getInt("gst_year")
            simpleMonth0 = st.getInt("gst_month")
            loadSimpleMonth(regenerate = false)
        } else chooseSimpleMonth(1)
    }

    private fun chooseSimpleMonth(choice: Int) {
        val cal = com.example.easy_billing.util.AppTime.calendar()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        if (simpleQuarterly) {
            val fi = (cal.get(java.util.Calendar.MONTH) + 9) % 12      // 0 = April
            cal.add(java.util.Calendar.MONTH, -(fi % 3))               // first month of this quarter
            if (choice == 1) cal.add(java.util.Calendar.MONTH, -3)
        } else if (choice == 1) cal.add(java.util.Calendar.MONTH, -1)
        simpleChoice = choice
        simpleYear = cal.get(java.util.Calendar.YEAR)
        simpleMonth0 = cal.get(java.util.Calendar.MONTH)
        loadSimpleMonth()
    }

    /** The 12 months before last month, newest first. */
    private fun showOlderMonths() {
        val labels = ArrayList<String>()
        val years = ArrayList<Int>()
        val months = ArrayList<Int>()
        val cal = com.example.easy_billing.util.AppTime.calendar()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val step = if (simpleQuarterly) 3 else 1
        if (simpleQuarterly) {
            val fi = (cal.get(java.util.Calendar.MONTH) + 9) % 12
            cal.add(java.util.Calendar.MONTH, -(fi % 3))
        }
        cal.add(java.util.Calendar.MONTH, -2 * step)   // this and last period have their own chips
        for (i in 0 until (if (simpleQuarterly) 8 else 12)) {
            labels.add(periodLabel(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH)))
            years.add(cal.get(java.util.Calendar.YEAR))
            months.add(cal.get(java.util.Calendar.MONTH))
            cal.add(java.util.Calendar.MONTH, -step)
        }
        val sel = if (simpleChoice == 2) years.indices.firstOrNull { years[it] == simpleYear && months[it] == simpleMonth0 } ?: -1 else -1
        com.example.easy_billing.ui.ThemedDropdown.show(chipGstOlder, labels, sel, false, 200) { idx ->
            simpleChoice = 2
            simpleYear = years[idx]
            simpleMonth0 = months[idx]
            loadSimpleMonth()
        }
    }

    private fun loadSimpleMonth(regenerate: Boolean = true) {
        val fyStart = if (simpleMonth0 >= 3) simpleYear else simpleYear - 1
        simpleFy = "$fyStart-${(fyStart + 1).toString().takeLast(2)}"
        val fiscalIdx = if (simpleMonth0 >= 3) simpleMonth0 - 3 else simpleMonth0 + 9
        simplePeriod = if (simpleQuarterly) quarterNames[fiscalIdx / 3] else fiscalMonthNames[fiscalIdx]
        simpleMonthLabel = periodLabel(simpleYear, simpleMonth0)
        simpleError = null
        autoSyncTried = false

        // Keep the accountant view in step with this choice.
        val rt = if (simpleQuarterly) "Quarterly" else "Monthly"
        viewModel1.setReturnType(rt)
        viewModel2.setReturnType(rt)
        isMonthly = !simpleQuarterly
        paintReturnType()
        fyIndex = currentFys().indexOf(simpleFy).coerceAtLeast(0)
        renderFy()
        periodIndex = currentPeriods().indexOf(simplePeriod).coerceAtLeast(0)
        buildPeriodStrip()
        viewModel1.setFinancialYear(simpleFy); viewModel1.setPeriod(simplePeriod)
        viewModel2.setFinancialYear(simpleFy); viewModel2.setPeriod(simplePeriod)

        com.example.easy_billing.util.UserEventLogger.logAction("GstReports", "simple_month_selected: $simpleFy $simplePeriod")
        val composition = getSharedPreferences("gst_prefs", MODE_PRIVATE).getBoolean("composition", false)
        if (!composition && (regenerate || (simpleReport1() == null && !viewModel1.isLoading.value))) viewModel1.generateReport()
        if (!composition && (regenerate || (simpleReport2() == null && !viewModel2.isLoading.value))) viewModel2.generateReport()
        renderSimple()
    }

    // ── Auto-sync: the report needs every bill on the server, so sync once and retry ──
    private var autoSyncTried = false
    private var simpleSyncing = false

    private fun syncThenReload() {
        simpleSyncing = true
        renderSimple()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.example.easy_billing.sync.SyncCoordinator.get(applicationContext).requestSync().join()
                }
            } catch (e: Exception) { /* offline: the retry below shows the normal message */ }
            simpleSyncing = false
            viewModel1.generateReport()
            viewModel2.generateReport()
            renderSimple()
        }
    }

    /** "April 2026" for monthly, "Apr 2026 – Jun 2026" for a quarter starting in [month0]. */
    private fun periodLabel(year: Int, month0: Int): String {
        val loc = java.util.Locale.getDefault()
        val c = java.util.Calendar.getInstance().apply { clear(); set(year, month0, 1) }
        if (!simpleQuarterly) return java.text.SimpleDateFormat("MMMM yyyy", loc).format(c.time)
        val f = java.text.SimpleDateFormat("MMM yyyy", loc)
        val start = f.format(c.time)
        c.add(java.util.Calendar.MONTH, 2)
        return start + " – " + f.format(c.time)
    }

    private fun setFilingMode(q: Boolean) {
        if (q == simpleQuarterly) return
        simpleQuarterly = q
        getSharedPreferences("gst_prefs", MODE_PRIVATE).edit().putBoolean("quarterly", q).apply()
        chooseSimpleMonth(1)
    }

    /** (year, month0) of the first month of this period (choice 0) or the previous one (choice 1). */
    private fun chipYearMonth(choice: Int): Pair<Int, Int> {
        val cal = com.example.easy_billing.util.AppTime.calendar()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        if (simpleQuarterly) {
            val fi = (cal.get(java.util.Calendar.MONTH) + 9) % 12
            cal.add(java.util.Calendar.MONTH, -(fi % 3))
            if (choice == 1) cal.add(java.util.Calendar.MONTH, -3)
        } else if (choice == 1) cal.add(java.util.Calendar.MONTH, -1)
        return cal.get(java.util.Calendar.YEAR) to cal.get(java.util.Calendar.MONTH)
    }

    private fun shortPeriod(year: Int, month0: Int): String {
        val f = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault())
        val c = java.util.Calendar.getInstance().apply { clear(); set(year, month0, 1) }
        if (!simpleQuarterly) return f.format(c.time)
        val start = f.format(c.time)
        c.add(java.util.Calendar.MONTH, 2)
        return start + "–" + f.format(c.time)
    }

    /** Plain-word, translated text for one validation issue (falls back to the English message). */
    private fun issueText(issue: Gstr1Validator.ValidationIssue): String {
        val res = when (issue.key) {
            "gstin_missing" -> R.string.gstv_gstin_missing
            "gstin_invalid" -> R.string.gstv_gstin_invalid
            "cust_gstin_invalid" -> R.string.gstv_cust_gstin_invalid
            "inv_no_blank" -> R.string.gstv_inv_no_blank
            "inv_date_missing" -> R.string.gstv_inv_date_missing
            "inv_value_zero" -> R.string.gstv_inv_value_zero
            "pos_missing" -> R.string.gstv_pos_missing
            "rate_unusual" -> R.string.gstv_rate_unusual
            "taxable_negative" -> R.string.gstv_taxable_negative
            "b2cl_small" -> R.string.gstv_b2cl_small
            "b2cs_pos_missing" -> R.string.gstv_b2cs_pos_missing
            "b2cs_negative" -> R.string.gstv_b2cs_negative
            "note_no_blank" -> R.string.gstv_note_no_blank
            "note_value_zero" -> R.string.gstv_note_value_zero
            "ur_type_blank" -> R.string.gstv_ur_type_blank
            "hsn_missing" -> R.string.gstv_hsn_missing
            "hsn_qty_zero" -> R.string.gstv_hsn_qty_zero
            "hsn_digits" -> R.string.gstv_hsn_digits
            "hsn_short" -> R.string.gstv_hsn_short
            "hsn_desc_missing" -> R.string.gstv_hsn_desc_missing
            "hsn_uqc_missing" -> R.string.gstv_hsn_uqc_missing
            "hsn_zero_rate" -> R.string.gstv_hsn_zero_rate
            "doc_nature_blank" -> R.string.gstv_doc_nature_blank
            "doc_zero" -> R.string.gstv_doc_zero
            else -> 0
        }
        if (res == 0) return issue.message
        return try { getString(res, *issue.args.toTypedArray()) } catch (e: Exception) { issue.message }
    }

    private fun sectionLabel(section: String): String {
        val res = when (section) {
            "General" -> R.string.gstv_sec_general
            "B2B" -> R.string.gstv_sec_b2b
            "B2CL" -> R.string.gstv_sec_b2cl
            "B2CS" -> R.string.gstv_sec_b2cs
            "CDNR" -> R.string.gstv_sec_cdnr
            "CDNUR" -> R.string.gstv_sec_cdnur
            "HSN(B2B)" -> R.string.gstv_sec_hsn_b2b
            "HSN(B2C)" -> R.string.gstv_sec_hsn_b2c
            "DOCS" -> R.string.gstv_sec_docs
            else -> 0
        }
        return if (res == 0) section else getString(res)
    }

    private fun simpleReport1(): Gstr1Report? = viewModel1.report.value?.takeIf {
        it.financialYear == simpleFy && it.period.equals(simplePeriod, ignoreCase = true)
    }

    private fun simpleReport2(): Gstr2Report? = viewModel2.report.value?.takeIf {
        it.financialYear == simpleFy && it.period.equals(simplePeriod, ignoreCase = true)
    }

    private fun money(v: Double) = "${CurrencyHelper.getCurrencySymbol(this)}%,.0f".format(v)

    private fun pill(bg: String, radiusDp: Float): android.graphics.drawable.GradientDrawable {
        val d = resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * d
            setColor(Color.parseColor(bg))
        }
    }

    private fun periodName(year: Int, month0: Int): String {
        val loc = java.util.Locale.getDefault()
        val c = java.util.Calendar.getInstance().apply { clear(); set(year, month0, 1) }
        if (!simpleQuarterly) return java.text.SimpleDateFormat("MMMM", loc).format(c.time)
        val f = java.text.SimpleDateFormat("MMM", loc)
        val start = f.format(c.time)
        c.add(java.util.Calendar.MONTH, 2)
        return start + " – " + f.format(c.time)
    }

    private fun paintChips() {
        val d = resources.displayMetrics.density
        val gp = getSharedPreferences("gst_prefs", MODE_PRIVATE)
        val loc = java.util.Locale.getDefault()
        val chips = listOf(chipGstThis, chipGstLast, chipGstOlder)
        val ids = listOf(
            Triple(R.id.numGstThis, R.id.monGstThis, R.id.tagGstThis),
            Triple(R.id.numGstLast, R.id.monGstLast, R.id.tagGstLast),
            Triple(R.id.numGstOlder, R.id.monGstOlder, R.id.tagGstOlder)
        )
        for (i in 0..2) {
            val sel = i == simpleChoice
            val num = findViewById<TextView>(ids[i].first)
            val mon = findViewById<TextView>(ids[i].second)
            val tag = findViewById<TextView>(ids[i].third)
            val ym: Pair<Int, Int>? = when {
                i < 2 -> chipYearMonth(i)
                simpleChoice == 2 -> simpleYear to simpleMonth0
                else -> null
            }
            var tagBg = "#D5DDD8"; var tagFg = "#5B6660"
            if (ym == null) {
                num.text = "⋯"
                mon.text = getString(if (simpleQuarterly) R.string.gst_simple_older_quarters else R.string.gst_simple_older)
                tag.text = getString(if (simpleQuarterly) R.string.gst_simple_chip_pick_q else R.string.gst_simple_chip_pick)
            } else {
                val (y, m) = ym
                num.text = if (simpleQuarterly) "Q" + (((m + 9) % 12) / 3 + 1) else "%02d".format(m + 1)
                mon.text = periodName(y, m)
                val filed = gp.getBoolean(com.example.easy_billing.gstr1.GstPeriod.filedKey(
                    com.example.easy_billing.gstr1.GstPeriod.fy(y, m),
                    com.example.easy_billing.gstr1.GstPeriod.periodName(m, simpleQuarterly)), false)
                if (filed) {
                    tag.text = getString(R.string.gst_simple_chip_filed); tagBg = "#E7F6EC"; tagFg = "#16A34A"
                } else if (i == 0) {
                    tag.text = getString(if (simpleQuarterly) R.string.gst_simple_this_quarter else R.string.gst_simple_this_month)
                } else {
                    val dc = java.util.Calendar.getInstance().apply {
                        clear(); set(y, m, 1)
                        add(java.util.Calendar.MONTH, if (simpleQuarterly) 3 else 1)
                        set(java.util.Calendar.DAY_OF_MONTH, if (simpleQuarterly) 22 else 20)
                    }
                    tag.text = getString(R.string.gst_simple_chip_due, java.text.SimpleDateFormat("d MMM", loc).format(dc.time))
                    tagBg = "#FAEEDA"; tagFg = "#8A6526"
                }
            }
            tag.background = pill(tagBg, 12f)
            tag.setTextColor(Color.parseColor(tagFg))
            num.setTextColor(Color.parseColor(if (sel) "#FFFFFF" else "#1A1A18"))
            mon.setTextColor(Color.parseColor(if (sel) "#FFFFFF" else "#4F5953"))
            chips[i].background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16f * d
                setColor(Color.parseColor(if (sel) "#0F6E56" else "#FFFFFF"))
                setStroke((1.5f * d).toInt(), Color.parseColor(if (sel) "#0F6E56" else "#D5DDD8"))
            }
        }
        tvGstAsk.text = getString(if (simpleQuarterly) R.string.gst_simple_ask_q else R.string.gst_simple_ask)
        tvGstFilingMode.text = getString(if (simpleQuarterly) R.string.gst_simple_every_quarter else R.string.gst_simple_every_month)
        tvGstFilingSub.text = getString(if (simpleQuarterly) R.string.gst_simple_sub_q else R.string.gst_simple_sub_m)
        val remOn = gp.getBoolean("reminders_on", true)
        tvGstReminder.text = getString(if (remOn) R.string.gst_simple_reminder_on else R.string.gst_simple_reminder_off)
        swGstReminder.isChecked = remOn
        listOf(segGstMonthly to !simpleQuarterly, segGstQuarterly to simpleQuarterly).forEach { (v, on) ->
            v.background = if (on) androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_gst_seg_on) else null
            v.setTextColor(Color.parseColor(if (on) "#0F6E56" else "#9FE1CB"))
        }
    }

    private fun checkGroup(key: String): Int = when {
        key == "gstin_missing" || key == "gstin_invalid" -> 0
        key == "cust_gstin_invalid" -> 2
        key.startsWith("hsn_") -> 3
        else -> 1
    }

    /** The "we checked" list: one line per area, green tick or a fix link. */
    private fun paintChecks(v: Gstr1Validator.ValidationResult) {
        llGstChecks.visibility = View.VISIBLE
        val dots = listOf(R.id.chkDot0, R.id.chkDot1, R.id.chkDot2, R.id.chkDot3)
        val txts = listOf(R.id.chkTxt0, R.id.chkTxt1, R.id.chkTxt2, R.id.chkTxt3)
        val fixes = listOf(R.id.chkFix0, R.id.chkFix1, R.id.chkFix2, R.id.chkFix3)
        val okRes = listOf(R.string.gst_chk_ok_gstin, R.string.gst_chk_ok_bills, R.string.gst_chk_ok_cust, R.string.gst_chk_ok_hsn)
        val badRes = listOf(R.string.gst_chk_bad_gstin, R.string.gst_chk_bad_bills, R.string.gst_chk_bad_cust, R.string.gst_chk_bad_hsn)
        for (g in 0..3) {
            val issues = v.issues.filter { checkGroup(it.key.orEmpty()) == g }
            val hasErr = issues.any { it.severity == Gstr1Validator.Severity.ERROR }
            val dot = findViewById<TextView>(dots[g])
            val txt = findViewById<TextView>(txts[g])
            val fix = findViewById<TextView>(fixes[g])
            val bg: String; val fg: String; val tc: String
            if (issues.isEmpty()) {
                dot.text = "✓"; bg = "#E7F6EC"; fg = "#16A34A"; tc = "#1A1A18"
                txt.text = getString(okRes[g])
                fix.visibility = View.GONE
            } else {
                dot.text = "!"
                if (hasErr) { bg = "#FBEDED"; fg = "#A32D2D"; tc = "#A32D2D" } else { bg = "#FAEEDA"; fg = "#8A6526"; tc = "#6B4A20" }
                txt.text = if (g == 0) getString(badRes[g]) else getString(badRes[g], issues.size)
                fix.visibility = View.VISIBLE
                fix.setTextColor(Color.parseColor(fg))
            }
            dot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor(bg))
            }
            dot.setTextColor(Color.parseColor(fg))
            txt.setTextColor(Color.parseColor(tc))
        }
    }

    private fun paintStatus(text: String, bg: String, fg: String) {
        tvGstStatus.visibility = View.VISIBLE
        tvGstStatus.text = text
        tvGstStatus.setBackgroundResource(R.drawable.bg_credit_avatar)
        tvGstStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bg))
        tvGstStatus.setTextColor(Color.parseColor(fg))
    }

    /** Red see-through box with a solid red border (used for "no sales this month"). */
    private fun paintNilStatus(text: String) {
        val d = resources.displayMetrics.density
        tvGstStatus.visibility = View.VISIBLE
        tvGstStatus.text = text
        tvGstStatus.backgroundTintList = null
        tvGstStatus.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 14f * d
            setColor(Color.parseColor("#1AA32D2D"))
            setStroke((1.5f * d).toInt(), Color.parseColor("#A32D2D"))
        }
        tvGstStatus.setTextColor(Color.parseColor("#A32D2D"))
    }

    private fun setCompositionMode(on: Boolean) {
        val vis = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.cardGstFiling).visibility = vis
        findViewById<View>(R.id.imgGstHistory).visibility = View.GONE
        tvGstDetailsToggle.visibility = vis
        if (on) {
            llAdvanced.visibility = View.GONE
            tvGstDetailsToggle.text = getString(R.string.gst_simple_details_show)
        }
    }

    private fun renderSimple() {
        if (!::llSimple.isInitialized) return
        paintChips()
        if (getSharedPreferences("gst_prefs", MODE_PRIVATE).getBoolean("composition", false)) {
            cardGstNumbers.visibility = View.GONE
            tvGstDue.visibility = View.GONE
            btnSendAccountant.visibility = View.GONE
            llGstLoading.visibility = View.GONE
            tvGstStatus.visibility = View.GONE
            rowGstFiled.visibility = View.GONE
            tvGstNotIncluded.visibility = View.GONE
            tvGstGstinWarn.visibility = View.GONE
            llGstComposition.visibility = View.VISIBLE
            // Composition shops file no monthly/quarterly return: nothing to pick, no reminders,
            // no accountant tables. Show only the banner, the two dates and the button.
            setCompositionMode(true)
            return
        }
        val loading = viewModel1.isLoading.value || viewModel2.isLoading.value || simpleSyncing
        val r1 = simpleReport1()

        tvGstGstinWarn.visibility = View.GONE
        llGstComposition.visibility = View.GONE
        setCompositionMode(false)
        if (r1 == null) {
            cardGstNumbers.visibility = View.GONE
            tvGstDue.visibility = View.GONE
            rowGstFiled.visibility = View.GONE
            tvGstNotIncluded.visibility = View.GONE
            btnSendAccountant.visibility = View.GONE
            llGstLoading.visibility = if (loading) View.VISIBLE else View.GONE
            val err = simpleError
            if (!loading && err != null) paintStatus(err + "\n" + getString(R.string.gst_simple_tap_retry), "#FBEDED", "#A32D2D")
            else tvGstStatus.visibility = View.GONE
            return
        }

        llGstLoading.visibility = if (loading) View.VISIBLE else View.GONE
        if (r1.gstin.isBlank()) {
            tvGstGstinWarn.visibility = View.VISIBLE
            tvGstGstinWarn.text = getString(R.string.gst_simple_gstin_missing)
        }
        cardGstNumbers.visibility = View.VISIBLE
        tvGstSelected.text = simpleMonthLabel
        val nil = com.example.easy_billing.gstr1.GstSimpleMath.isNil(r1)
        val collected = com.example.easy_billing.gstr1.GstSimpleMath.netTax(r1)
        tvGstSales.text = getString(R.string.gst_simple_on_sales, money(com.example.easy_billing.gstr1.GstSimpleMath.netTaxable(r1)))
        tvGstBills.text = getString(R.string.gst_simple_bills_n, r1.totalInvoiceCount.toString())
        tvGstCollected.text = money(collected)
        val r2 = simpleReport2()
        if (r2 != null) {
            val credit = r2.totalTax
            tvGstCredit.text = money(credit)
            tvGstToPay.text = money((collected - credit).coerceAtLeast(0.0))
        } else {
            tvGstCredit.text = getString(R.string.gst_simple_credit_missing)
            tvGstToPay.text = "—"
        }
        tvGstToPayNote.text = getString(R.string.gst_simple_topay_note)

        // One plain status line
        val v = viewModel1.validationResult.value
        llGstChecks.visibility = View.GONE
        when {
            nil -> paintNilStatus(getString(R.string.gst_simple_nil))
            v == null -> tvGstStatus.visibility = View.GONE
            else -> { tvGstStatus.visibility = View.GONE; paintChecks(v) }
        }

        // Due dates: GSTR-1 on the 11th, GSTR-3B on the 20th of the following month.
        // Quarterly (QRMP): GSTR-1 on the 13th, GSTR-3B on the 22nd (24th in some states),
        // of the month after the quarter ends.
        val dueCal = java.util.Calendar.getInstance().apply {
            clear(); set(simpleYear, simpleMonth0, 1); add(java.util.Calendar.MONTH, if (simpleQuarterly) 3 else 1)
        }
        val df = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        dueCal.set(java.util.Calendar.DAY_OF_MONTH, if (simpleQuarterly) 13 else 11); val d1 = dueCal.time
        dueCal.set(java.util.Calendar.DAY_OF_MONTH, if (simpleQuarterly) 22 else 20); val d3 = dueCal.time
        val late = java.util.Date(com.example.easy_billing.util.appNow()).after(java.util.Date(d3.time + 24L * 3600 * 1000))
        // The "Due on the GST website" sentence is gone; the amber tag beside the amount shows the due date.
        // Only the "dates have passed" warning remains, and only while the period is late.
        tvGstDue.visibility = if (late) View.VISIBLE else View.GONE
        if (late) tvGstDue.text = getString(R.string.gst_simple_due_late, df.format(d1), df.format(d3))

        // "Filed" mark: stops the reminders for this period and hides the due line.
        val filed = getSharedPreferences("gst_prefs", MODE_PRIVATE)
            .getBoolean(com.example.easy_billing.gstr1.GstPeriod.filedKey(simpleFy, simplePeriod), false)
        val dd = resources.displayMetrics.density
        rowGstFiled.visibility = View.VISIBLE
        swGstFiled.isChecked = filed
        tvGstFiledSub.text = getString(if (filed) R.string.gst_simple_filed_sub_on else R.string.gst_simple_filed_sub_off)
        tvGstFiledSub.setTextColor(Color.parseColor(if (filed) "#16A34A" else "#79897F"))
        if (filed) tvGstDue.visibility = View.GONE
        // Tag + colour of the "You pay" box: green when filed, amber when due or late.
        val daysLate = ((com.example.easy_billing.util.appNow() - d3.time) / (24L * 3600 * 1000)).toInt().coerceAtLeast(1)
        val warn = late && !filed
        if (filed) {
            tvGstToPayTag.text = getString(R.string.gst_simple_chip_filed)
            tvGstToPayTag.background = pill("#E7F6EC", 12f); tvGstToPayTag.setTextColor(Color.parseColor("#16A34A"))
        } else {
            tvGstToPayTag.text = if (late) getString(R.string.gst_simple_tag_late, daysLate)
                else getString(R.string.gst_simple_chip_due, java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(d3))
            tvGstToPayTag.background = pill("#FAEEDA", 12f); tvGstToPayTag.setTextColor(Color.parseColor("#8A6526"))
        }
        boxGstTotal.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 14f * dd
            setColor(Color.parseColor(if (warn) "#FAEEDA" else "#F2FBF5"))
            setStroke((1.5f * dd).toInt(), Color.parseColor(if (warn) "#F3D9A8" else "#CDEBD9"))
        }
        lblGstYouPay.setTextColor(Color.parseColor(if (warn) "#8A6526" else "#5E7A6E"))
        tvGstToPay.setTextColor(Color.parseColor(if (warn) "#6B4A20" else "#085041"))
        tvGstNotIncluded.visibility = View.VISIBLE

        btnSendAccountant.visibility = View.VISIBLE
        btnSendAccountant.isEnabled = !sendingToAccountant
        btnSendAccountant.text = getString(if (sendingToAccountant) R.string.gst_simple_send_busy else R.string.gst_simple_send)
    }

    private fun sendToAccountant() {
        val r1 = simpleReport1() ?: return
        if (sendingToAccountant) return
        sendingToAccountant = true
        renderSimple()
        val r2 = simpleReport2()
        lifecycleScope.launch {
            try {
                val nil = com.example.easy_billing.gstr1.GstSimpleMath.isNil(r1)
                val uris = if (nil) emptyList() else withContext(Dispatchers.IO) {
                    listOf(
                        com.example.easy_billing.gstr1.Gstr1ExcelExporter.export(applicationContext, r1).uri,
                        com.example.easy_billing.gstr1.Gstr1JsonExporter.export(applicationContext, r1).uri
                    )
                }
                val collected = com.example.easy_billing.gstr1.GstSimpleMath.netTax(r1)
                val baseText = if (nil) {
                    getString(R.string.gst_simple_send_nil_msg, simpleMonthLabel, r1.gstin)
                } else {
                    val toPay = if (r2 != null) money((collected - r2.totalTax).coerceAtLeast(0.0)) else "—"
                    getString(
                        R.string.gst_simple_send_msg, simpleMonthLabel, r1.gstin,
                        money(com.example.easy_billing.gstr1.GstSimpleMath.netTaxable(r1)),
                        r1.totalInvoiceCount, money(collected), toPay
                    )
                }
                // Things this report does not cover, so the accountant checks them.
                val text = if (nil) baseText else baseText + "\n\n" + getString(R.string.gst_simple_not_included)
                val intent = if (uris.isEmpty()) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        // WhatsApp drops the files when a text is sent along with several files,
                        // so the summary goes to the clipboard instead and is pasted in the chat.
                        val clip = android.content.ClipData.newRawUri("report", uris[0])
                        for (i in 1 until uris.size) clip.addItem(android.content.ClipData.Item(uris[i]))
                        clipData = clip
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }.also {
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("GST summary", text))
                        Toast.makeText(this@GstReportsActivity, getString(R.string.gst_simple_send_copied), Toast.LENGTH_LONG).show()
                    }
                }
                com.example.easy_billing.util.UserEventLogger.logAction("GstReports", "send_to_accountant: $simpleFy $simplePeriod nil=$nil")
                startActivity(Intent.createChooser(intent, getString(R.string.gst_simple_send_chooser)))
            } catch (e: Exception) {
                Toast.makeText(this@GstReportsActivity, getString(R.string.gst_simple_send_failed), Toast.LENGTH_LONG).show()
            } finally {
                sendingToAccountant = false
                renderSimple()
            }
        }
    }

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
    private var showEmptySheets = false
    private var sectionLabels: List<String> = emptyList()
    private var currentSection = 0

    private fun buildSectionChips(labels: List<String>) {
        sectionLabels = labels
        chipGroupSections.removeAllViews()
        val d = resources.displayMetrics.density
        val font = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.googlesans_semibold)
        val counts = sectionCounts()
        val showAll = showEmptySheets || counts.all { it == 0 }

        labels.forEachIndexed { index, label ->
            val n = counts.getOrNull(index) ?: 0
            val chip = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((14 * d).toInt(), (9 * d).toInt(), (10 * d).toInt(), (9 * d).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * d).toInt() }
                isClickable = true
                visibility = if (n > 0 || showAll) View.VISIBLE else View.GONE
                setOnClickListener { viewPager.setCurrentItem(index, false); selectSection(index) }
            }
            chip.addView(TextView(this).apply {
                text = label
                textSize = 14.5f
                typeface = font
                includeFontPadding = false
            })
            chip.addView(TextView(this).apply {
                text = n.toString()
                textSize = 12f
                typeface = font
                includeFontPadding = false
                gravity = android.view.Gravity.CENTER
                setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (8 * d).toInt() }
            })
            chipGroupSections.addView(chip)
        }

        // "Show empty sheets" link below the chips, only when some sheets are empty
        val emptyCount = counts.count { it == 0 }
        val toggle = findViewById<TextView>(R.id.tvSheetsToggle)
        if (emptyCount > 0 && counts.any { it > 0 }) {
            toggle.text = if (showEmptySheets) getString(R.string.gst_sheets_hide_empty)
                          else getString(R.string.gst_sheets_show_empty, emptyCount)
            toggle.visibility = View.VISIBLE
            toggle.setOnClickListener {
                showEmptySheets = !showEmptySheets
                buildSectionChips(sectionLabels)
            }
        } else {
            toggle.visibility = View.GONE
        }

        val first = counts.indexOfFirst { it > 0 }.coerceAtLeast(0)
        val start = if (currentSection in labels.indices && (showAll || (counts.getOrNull(currentSection) ?: 0) > 0)) currentSection else first
        if (viewPager.currentItem != start) viewPager.setCurrentItem(start, false)
        selectSection(start)
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
        currentSection = index
        val d = resources.displayMetrics.density
        for (i in 0 until chipGroupSections.childCount) {
            val chip = chipGroupSections.getChildAt(i) as? android.widget.LinearLayout ?: continue
            val label = chip.getChildAt(0) as TextView
            val badge = chip.getChildAt(1) as TextView
            val selected = i == index
            if (selected) chip.visibility = View.VISIBLE

            chip.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 22f * d
                setColor(Color.parseColor(if (selected) "#0F6E56" else "#FFFFFF"))
                setStroke((1.5f * d).toInt(), Color.parseColor(if (selected) "#0F6E56" else "#D5DDD8"))
            }
            label.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#1A1A18"))

            badge.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 10f * d
                setColor(Color.parseColor(if (selected) "#38FFFFFF" else "#E3F3EE"))
            }
            badge.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#0F6E56"))
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
                if (isGstr1) tvGstin.text = if (gstin.isNotBlank()) gstin else getString(R.string.gst_not_configured)
            }
        }
        lifecycleScope.launch {
            viewModel1.isLoading.collectLatest { loading ->
                if (isGstr1) handleLoading(loading)
            }
        }
        lifecycleScope.launch { viewModel1.report.collectLatest { if (isGstr1 && it != null) bindReport1(it) } }
        lifecycleScope.launch { viewModel1.validationResult.collectLatest { if (isGstr1 && it != null) bindValidation1(it) } }
        lifecycleScope.launch {
            viewModel1.error.collectLatest {
                if (it != null) {
                    viewModel1.clearError()
                    if (it == getString(R.string.gstr1_vm_error_unsynced_data) && !autoSyncTried) {
                        autoSyncTried = true
                        syncThenReload()
                    } else {
                        simpleError = it
                        renderSimple()
                        Toast.makeText(this@GstReportsActivity, it, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        lifecycleScope.launch { viewModel1.exportEvent.collectLatest { if (isGstr1 && it != null) { handleExportEvent1(it); viewModel1.clearExportEvent() } } }
        lifecycleScope.launch { viewModel1.drafts.collectLatest { if (isGstr1) bindDrafts1(it) } }

        // GSTR-2
        lifecycleScope.launch {
            viewModel2.gstin.collectLatest { gstin ->
                if (!isGstr1) tvGstin.text = if (gstin.isNotBlank()) gstin else getString(R.string.gst_not_configured)
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
        btnGenerate.text = getString(if (loading) R.string.gst_btn_generating else R.string.gst_btn_generate)
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Report binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindReport1(report: Gstr1Report) {
        // Summary hero
        cardSummary.visibility = View.GONE
        tvSummaryHeroLabel.text   = getString(R.string.gst_hero_label_total_tax)
        tvSummaryTax.text         = "${CurrencyHelper.getCurrencySymbol(this)}%,.0f".format(report.totalTax)
        tvSummaryTaxable.text     = getString(R.string.gst_summary_taxable_suffix).format(report.totalTaxable)
        tvSummaryInvoices.text    = report.totalInvoiceCount.toString()
        tvSummaryCreditNotes.text = report.totalCreditNotes.toString()

        // Section chips + pages (rebuild so the count badges pick up this report)
        buildSectionChips(Gstr1SheetTabAdapter.TAB_LABELS.toList())
        scrollSections.visibility = View.VISIBLE
        findViewById<View>(R.id.tvSheetsHeading).visibility = View.VISIBLE
        cardSection.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE

        setActionButtonsEnabled(true)
    }

    private fun bindValidation1(result: Gstr1Validator.ValidationResult) {
        llValidationBanner.visibility = View.GONE

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
                    getString(R.string.gst_banner_errors, result.errorCount, result.warningCount)
                paint("#FBEDED", "#A32D2D")
            }
            result.hasWarnings -> {
                tvValidationStatus.text =
                    getString(R.string.gst_banner_warnings, result.warningCount)
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
        llDraftsSection.visibility = View.GONE
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
            message = getString(R.string.gst_delete_draft_message, draft.period, draft.financialYear),
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
                tvMessage.text = getString(R.string.gst_dlg_errors, result.errorCount, result.warningCount)
            }
            result.hasWarnings -> {
                badgeFrame.background = ContextCompat.getDrawable(this, R.drawable.bg_circle_soft_gold)
                ivBadge.setImageResource(R.drawable.ic_lc_alert_triangle)
                ivBadge.imageTintList = ColorStateList.valueOf(Color.parseColor("#8A6526"))
                tvTitleBold.text = getString(R.string.gst_validation_title_review)
                tvTitleAcc.text = getString(R.string.gst_validation_title_warnings)
                tvMessage.text = getString(R.string.gst_dlg_warnings, result.warningCount)
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
                row.findViewById<TextView>(R.id.tvSection).text = sectionLabel(issue.section)
                row.findViewById<TextView>(R.id.tvMessage).text =
                    if (issue.rowHint.isNotBlank()) "${issueText(issue)} (${issue.rowHint})" else issueText(issue)

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
                        setBackgroundColor(Color.parseColor("#E3E8E4"))
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
                    message = getString(R.string.gst_csv_ready_list, event.files.size, event.files.keys.joinToString(", ")),
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
        cardSummary.visibility = View.GONE
        tvSummaryHeroLabel.text   = getString(R.string.gst_hero_label_itc)
        tvSummaryTax.text         = "${CurrencyHelper.getCurrencySymbol(this)}%,.0f".format(report.totalTax)
        tvSummaryTaxable.text     = getString(R.string.gst_summary_taxable_suffix).format(report.totalTaxable)
        tvSummaryInvoices.text    = report.totalInvoiceCount.toString()
        tvSummaryCreditNotes.text = report.totalCreditNotes.toString()

        // Section chips + pages (rebuild so the count badges pick up this report)
        buildSectionChips(Gstr2SheetTabAdapter.TAB_LABELS.toList())
        scrollSections.visibility = View.VISIBLE
        findViewById<View>(R.id.tvSheetsHeading).visibility = View.VISIBLE
        cardSection.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE

        setActionButtonsEnabled(true)
    }

    private fun bindDrafts2(drafts: List<Gstr2DraftEntity>) {
        llDraftsSection.visibility = View.GONE
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
                    message = getString(R.string.gst_csv_ready, event.files.size),
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
