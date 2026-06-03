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

    private val viewModel: Gstr1ViewModel by viewModels()

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
        setupTabs()
        setupButtons()
        observeViewModel()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ─────────────────────────────────────────────────────────────────────────
    //  View binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
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
        // FY spinner
        val fyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, viewModel.availableFYs)
        fyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFY.adapter = fyAdapter
        spinnerFY.setSelection(0)
        spinnerFY.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                viewModel.setFinancialYear(viewModel.availableFYs[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Period spinner
        refreshPeriodSpinner()

        // Return type chips
        chipGroupReturnType.setOnCheckedStateChangeListener { _, _ ->
            val isMonthly = chipMonthly.isChecked
            viewModel.setReturnType(if (isMonthly) "Monthly" else "Quarterly")
            refreshPeriodSpinner()
        }
    }

    private fun refreshPeriodSpinner() {
        val periods = viewModel.availablePeriods
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, periods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPeriod.adapter = adapter
        spinnerPeriod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                viewModel.setPeriod(periods[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Tabs
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupTabs() {
        tabAdapter = Gstr1SheetTabAdapter(this)
        viewPager.adapter = tabAdapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = Gstr1SheetTabAdapter.TAB_LABELS[position]
        }.attach()

        // Hide tabs/pager until report is generated
        tabLayout.visibility = View.GONE
        viewPager.visibility = View.GONE
        cardSummary.visibility = View.GONE
        llValidationBanner.visibility = View.GONE
        setActionButtonsEnabled(false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Buttons
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnGenerate.setOnClickListener { viewModel.generateReport() }
        btnValidate.setOnClickListener { viewModel.validateReport() }
        btnSaveDraft.setOnClickListener { viewModel.saveDraft() }
        btnExportCsv.setOnClickListener { viewModel.exportCsv() }
        btnExportExcel.setOnClickListener { viewModel.exportExcel() }
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

        lifecycleScope.launch {
            viewModel.gstin.collectLatest { gstin ->
                tvGstin.text = if (gstin.isNotBlank()) "GSTIN: $gstin" else "GST Profile not configured"
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                progressGenerate.visibility = if (loading) View.VISIBLE else View.GONE
                btnGenerate.isEnabled = !loading
                btnGenerate.text = if (loading) "Generating…" else "Generate GSTR-1"
            }
        }

        lifecycleScope.launch {
            viewModel.report.collectLatest { report ->
                report ?: return@collectLatest
                bindReport(report)
            }
        }

        lifecycleScope.launch {
            viewModel.validationResult.collectLatest { result ->
                result ?: return@collectLatest
                bindValidation(result)
            }
        }

        lifecycleScope.launch {
            viewModel.error.collectLatest { error ->
                error ?: return@collectLatest
                Toast.makeText(this@GstReportsActivity, error, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        lifecycleScope.launch {
            viewModel.exportEvent.collectLatest { event ->
                event ?: return@collectLatest
                handleExportEvent(event)
                viewModel.clearExportEvent()
            }
        }

        lifecycleScope.launch {
            viewModel.drafts.collectLatest { drafts ->
                bindDrafts(drafts)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Report binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindReport(report: Gstr1Report) {
        // Summary card
        cardSummary.visibility = View.VISIBLE
        tvSummaryInvoices.text    = report.totalInvoiceCount.toString()
        tvSummaryTaxable.text     = "₹%,.2f".format(report.totalTaxable)
        tvSummaryTax.text         = "₹%,.2f".format(report.totalTax)
        tvSummaryCreditNotes.text = report.totalCreditNotes.toString()

        // Tabs
        tabLayout.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE
        tabAdapter.updateReport(report)

        setActionButtonsEnabled(true)
    }

    private fun bindValidation(result: Gstr1Validator.ValidationResult) {
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

    private fun bindDrafts(drafts: List<Gstr1DraftEntity>) {
        llDraftsSection.visibility = if (drafts.isEmpty()) View.GONE else View.VISIBLE
        rvDrafts.layoutManager = LinearLayoutManager(this)
        rvDrafts.adapter = Gstr1DraftsAdapter(drafts,
            onOpen   = { viewModel.loadDraftById(it.id) },
            onDelete = { confirmDeleteDraft(it) }
        )
    }

    private fun confirmDeleteDraft(draft: Gstr1DraftEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Draft?")
            .setMessage("Delete GSTR-1 draft for ${draft.period} ${draft.financialYear}?")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteDraft(draft.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Export events
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleExportEvent(event: Gstr1ViewModel.ExportEvent) {
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
}
