package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.gstr1.Gstr1DraftEntity
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
import com.google.android.material.button.MaterialButtonToggleGroup

import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
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
    private lateinit var toggleReportType: MaterialButtonToggleGroup
    private lateinit var tabAdapter2: Gstr2SheetTabAdapter


    // ── Header selectors ──────────────────────────────────────────────────────
    private lateinit var spinnerFY: Spinner
    private lateinit var spinnerPeriod: Spinner
    private lateinit var chipGroupReturnType: ChipGroup
    private lateinit var chipMonthly: Chip
    private lateinit var chipQuarterly: Chip
    private lateinit var btnGenerate: MaterialButton
    private lateinit var progressGenerate: CircularProgressIndicator

    // ── GSTIN display ─────────────────────────────────────────────────────────
    private lateinit var tvGstin: TextView

    // ── Summary card ──────────────────────────────────────────────────────────
    private lateinit var cardSummary: View
    private lateinit var tvSummaryInvoices: TextView
    private lateinit var tvSummaryTaxable: TextView
    private lateinit var tvSummaryTax: TextView
    private lateinit var tvSummaryCreditNotes: TextView

    // ── Validation banner ─────────────────────────────────────────────────────
    private lateinit var llValidationBanner: LinearLayout
    private lateinit var tvValidationStatus: TextView

    // ── Sheet tabs ────────────────────────────────────────────────────────────
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var tabAdapter: Gstr1SheetTabAdapter

    // ── Action buttons ────────────────────────────────────────────────────────
    private lateinit var btnValidate: MaterialButton
    private lateinit var btnSaveDraft: MaterialButton
    private lateinit var btnExportCsv: MaterialButton
    private lateinit var btnExportExcel: MaterialButton

    // ── Drafts ────────────────────────────────────────────────────────────────
    private lateinit var llDraftsSection: LinearLayout
    private lateinit var rvDrafts: RecyclerView

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gst_reports)

        bindViews()
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = "GSTR-1 Report"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setupSelectors()

        toggleReportType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isGstr1 = checkedId == R.id.btnGstr1
                supportActionBar?.title = if (isGstr1) "GSTR-1 Report" else "GSTR-2 Report"
                setupTabs() // recreate tabs
                refreshPeriodSpinner()
                // Reset UI
                cardSummary.visibility = View.GONE
                llValidationBanner.visibility = View.GONE
                tabLayout.visibility = View.GONE
                viewPager.visibility = View.GONE
                llDraftsSection.visibility = View.GONE
                setActionButtonsEnabled(false)
            }
        }

        setupTabs()
        setupButtons()
        observeViewModel()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ─────────────────────────────────────────────────────────────────────────
    //  View binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        
        toggleReportType        = findViewById(R.id.toggleReportType)

        spinnerFY               = findViewById(R.id.spinnerFY)
        spinnerPeriod           = findViewById(R.id.spinnerPeriod)
        chipGroupReturnType     = findViewById(R.id.chipGroupReturnType)
        chipMonthly             = findViewById(R.id.chipMonthly)
        chipQuarterly           = findViewById(R.id.chipQuarterly)
        btnGenerate             = findViewById(R.id.btnGenerate)
        progressGenerate        = findViewById(R.id.progressGenerate)
        tvGstin                 = findViewById(R.id.tvGstin)
        cardSummary             = findViewById(R.id.cardSummary)
        tvSummaryInvoices       = findViewById(R.id.tvSummaryInvoices)
        tvSummaryTaxable        = findViewById(R.id.tvSummaryTaxable)
        tvSummaryTax            = findViewById(R.id.tvSummaryTax)
        tvSummaryCreditNotes    = findViewById(R.id.tvSummaryCreditNotes)
        llValidationBanner      = findViewById(R.id.llValidationBanner)
        tvValidationStatus      = findViewById(R.id.tvValidationStatus)
        tabLayout               = findViewById(R.id.tabLayout)
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


    private fun setupSelectors() {
        toggleReportType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isGstr1 = checkedId == R.id.btnGstr1
                supportActionBar?.title = if (isGstr1) "GSTR-1 Report" else "GSTR-2 Report"
                setupTabs() // recreate tabs
                refreshPeriodSpinner()
                
                // Re-bind FY spinner adapter
                val fys = if(isGstr1) viewModel1.availableFYs else viewModel2.availableFYs
                val fyAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, fys)
                fyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerFY.adapter = fyAdapter
                spinnerFY.setSelection(0)

                // Reset UI
                cardSummary.visibility = android.view.View.GONE
                llValidationBanner.visibility = android.view.View.GONE
                tabLayout.visibility = android.view.View.GONE
                viewPager.visibility = android.view.View.GONE
                llDraftsSection.visibility = android.view.View.GONE
                setActionButtonsEnabled(false)
            }
        }

        // FY spinner (initial)
        val initialFys = if(isGstr1) viewModel1.availableFYs else viewModel2.availableFYs
        val fyAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, initialFys)
        fyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFY.adapter = fyAdapter
        spinnerFY.setSelection(0)
        spinnerFY.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val fys = if(isGstr1) viewModel1.availableFYs else viewModel2.availableFYs
                if (isGstr1) viewModel1.setFinancialYear(fys[pos]) else viewModel2.setFinancialYear(fys[pos])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // Period spinner
        refreshPeriodSpinner()

        // Return type chips
        chipGroupReturnType.setOnCheckedStateChangeListener { _, _ ->
            val isMonthly = chipMonthly.isChecked
            if (isGstr1) viewModel1.setReturnType(if (isMonthly) "Monthly" else "Quarterly") 
            else viewModel2.setReturnType(if (isMonthly) "Monthly" else "Quarterly")
            refreshPeriodSpinner()
        }
    }

    private fun refreshPeriodSpinner() {
        val periods = if(isGstr1) viewModel1.availablePeriods else viewModel2.availablePeriods
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, periods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPeriod.adapter = adapter
        spinnerPeriod.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val pds = if(isGstr1) viewModel1.availablePeriods else viewModel2.availablePeriods
                if (isGstr1) viewModel1.setPeriod(pds[pos]) else viewModel2.setPeriod(pds[pos])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Tabs
    // ─────────────────────────────────────────────────────────────────────────

    
    private fun setupTabs() {
        if (isGstr1) {
            tabAdapter = Gstr1SheetTabAdapter(this)
            viewPager.adapter = tabAdapter
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = Gstr1SheetTabAdapter.TAB_LABELS[position]
            }.attach()
        } else {
            tabAdapter2 = Gstr2SheetTabAdapter(this)
            viewPager.adapter = tabAdapter2
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = Gstr2SheetTabAdapter.TAB_LABELS[position]
            }.attach()
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Buttons
    // ─────────────────────────────────────────────────────────────────────────

    
    private fun setupButtons() {
        btnGenerate.setOnClickListener { if (isGstr1) viewModel1.generateReport() else viewModel2.generateReport() }
        btnValidate.setOnClickListener { if (isGstr1) viewModel1.validateReport() else Unit } // No manual validate for GSTR-2 yet
        btnSaveDraft.setOnClickListener { if (isGstr1) viewModel1.saveDraft() else viewModel2.saveDraft() }
        btnExportCsv.setOnClickListener { if (isGstr1) viewModel1.exportCsv() else viewModel2.exportCsv() }
        btnExportExcel.setOnClickListener { if (isGstr1) viewModel1.exportExcel() else viewModel2.exportExcel() }
    }


    private fun setActionButtonsEnabled(enabled: Boolean) {
        btnValidate.isEnabled = enabled
        btnSaveDraft.isEnabled = enabled
        btnExportCsv.isEnabled = enabled
        btnExportExcel.isEnabled = enabled
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ViewModel observation
    // ─────────────────────────────────────────────────────────────────────────

    
    private fun observeViewModel() {
        // GSTR-1
        lifecycleScope.launch {
            viewModel1.gstin.collectLatest { gstin ->
                if (isGstr1) tvGstin.text = if (gstin.isNotBlank()) "GSTIN: $gstin" else "GST Profile not configured"
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
                if (!isGstr1) tvGstin.text = if (gstin.isNotBlank()) "GSTIN: $gstin" else "GST Profile not configured"
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
        btnGenerate.text = if (loading) "Generating…" else "Generate"
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Report binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindReport1(report: Gstr1Report) {
        // Summary card
        cardSummary.visibility = View.VISIBLE
        tvSummaryInvoices.text    = report.totalInvoiceCount.toString()
        tvSummaryTaxable.text     = "₹%,.2f".format(report.totalTaxable)
        tvSummaryTax.text         = "₹%,.2f".format(report.totalTax)
        tvSummaryCreditNotes.text = report.totalCreditNotes.toString()

        // Tabs
        tabLayout.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE

        setActionButtonsEnabled(true)
    }

    private fun bindValidation1(result: Gstr1Validator.ValidationResult) {
        llValidationBanner.visibility = View.VISIBLE

        when {
            result.hasErrors -> {
                tvValidationStatus.text =
                    "⚠ ${result.errorCount} error(s), ${result.warningCount} warning(s) — Fix errors before filing"
                llValidationBanner.setBackgroundColor(getColor(R.color.error_banner_bg))
            }
            result.hasWarnings -> {
                tvValidationStatus.text =
                    "⚡ ${result.warningCount} warning(s) — Review before filing"
                llValidationBanner.setBackgroundColor(getColor(R.color.warning_banner_bg))
            }
            else -> {
                tvValidationStatus.text = "✓ All checks passed — Ready to export"
                llValidationBanner.setBackgroundColor(getColor(R.color.success_banner_bg))
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
        AlertDialog.Builder(this)
            .setTitle("Delete Draft?")
            .setMessage("Delete GSTR-1 draft for ${draft.period} ${draft.financialYear}?")
            .setPositiveButton("Delete") { _, _ -> viewModel1.deleteDraft(draft.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Export events
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleExportEvent1(event: Gstr1ViewModel.ExportEvent) {
        when (event) {
            is Gstr1ViewModel.ExportEvent.DraftSaved -> {
                Toast.makeText(this, "Draft saved successfully.", Toast.LENGTH_SHORT).show()
            }
            is Gstr1ViewModel.ExportEvent.CsvExported -> {
                AlertDialog.Builder(this)
                    .setTitle("CSV Export Complete")
                    .setMessage(
                        "${event.files.size} CSV file(s) written to:\n${event.directory}\n\n" +
                        "Sections: ${event.files.keys.joinToString(", ")}"
                    )
                    .setPositiveButton("Share All") { _, _ ->
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "text/csv"
                            val uriList = ArrayList(event.files.values)
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, "Share GSTR-1 CSVs"))
                    }
                    .setNegativeButton("OK", null)
                    .show()
            }
            is Gstr1ViewModel.ExportEvent.ExcelExported -> {
                AlertDialog.Builder(this)
                    .setTitle("Excel Export Complete")
                    .setMessage("Workbook saved to:\n${event.path}")
                    .setPositiveButton("Open") { _, _ ->
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(event.uri,
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "No app found to open .xlsx files.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("OK", null)
                    .show()
            }
        }
    }


    private fun bindReport2(report: Gstr2Report) {
        cardSummary.visibility = View.VISIBLE
        tvSummaryInvoices.text    = report.totalInvoiceCount.toString()
        tvSummaryTaxable.text     = "₹%,.2f".format(report.totalTaxable)
        tvSummaryTax.text         = "N/A"
        tvSummaryCreditNotes.text = report.totalCreditNotes.toString()

        tabLayout.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE

        setActionButtonsEnabled(true)
    }

    private fun bindDrafts2(drafts: List<Gstr2DraftEntity>) {
        llDraftsSection.visibility = if (drafts.isEmpty()) View.GONE else View.VISIBLE
        rvDrafts.layoutManager = LinearLayoutManager(this)
        rvDrafts.adapter = Gstr2DraftsAdapter(drafts,
            onOpen   = { /* viewModel2.loadDraftById(it.id) */ },
            onDelete = {
                AlertDialog.Builder(this)
                    .setTitle("Delete Draft?")
                    .setMessage("Delete GSTR-2 draft?")
                    .setPositiveButton("Delete") { _, _ -> viewModel2.deleteDraft(it) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
    }

    private fun handleExportEvent2(event: Gstr2ViewModel.ExportEvent) {
        when (event) {
            is Gstr2ViewModel.ExportEvent.DraftSaved -> Toast.makeText(this, "Draft saved successfully.", Toast.LENGTH_SHORT).show()
            is Gstr2ViewModel.ExportEvent.CsvExported -> {
                AlertDialog.Builder(this)
                    .setTitle("CSV Export Complete")
                    .setMessage("${event.files.size} CSV file(s) written to:\n${event.directory}")
                    .setPositiveButton("Share All") { _, _ ->
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "text/csv"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(event.files.values))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, "Share GSTR-2 CSVs"))
                    }
                    .setNegativeButton("OK", null).show()
            }
            is Gstr2ViewModel.ExportEvent.ExcelExported -> {
                AlertDialog.Builder(this)
                    .setTitle("Excel Export Complete")
                    .setMessage("Workbook saved to:\n${event.path}")
                    .setPositiveButton("Open") { _, _ ->
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(event.uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try { startActivity(intent) } catch (e: Exception) { Toast.makeText(this, "No app found.", Toast.LENGTH_SHORT).show() }
                    }
                    .setNegativeButton("OK", null).show()
            }
        }
    }
}
