package com.example.easy_billing.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.easy_billing.R
import com.example.easy_billing.gstr1.Gstr1CsvExporter
import com.example.easy_billing.gstr1.Gstr1DraftEntity
import com.example.easy_billing.gstr1.Gstr1ExcelExporter
import com.example.easy_billing.gstr1.Gstr1Generator
import com.example.easy_billing.gstr1.Gstr1Report
import com.example.easy_billing.gstr1.Gstr1Repository
import com.example.easy_billing.gstr1.Gstr1Validator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Gstr1ViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Gstr1Repository(app)

    // ── Period selectors ──────────────────────────────────────────────────────

    private val _financialYear = MutableStateFlow("")
    val financialYear: StateFlow<String> = _financialYear.asStateFlow()

    private val _period = MutableStateFlow("")
    val period: StateFlow<String> = _period.asStateFlow()

    private val _returnType = MutableStateFlow("Monthly")
    val returnType: StateFlow<String> = _returnType.asStateFlow()

    private val _gstin = MutableStateFlow("")
    val gstin: StateFlow<String> = _gstin.asStateFlow()

    // ── Report state ──────────────────────────────────────────────────────────

    private val _report = MutableStateFlow<Gstr1Report?>(null)
    val report: StateFlow<Gstr1Report?> = _report.asStateFlow()

    private val _validationResult = MutableStateFlow<Gstr1Validator.ValidationResult?>(null)
    val validationResult: StateFlow<Gstr1Validator.ValidationResult?> = _validationResult.asStateFlow()

    // ── UI state ──────────────────────────────────────────────────────────────

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _exportEvent = MutableStateFlow<ExportEvent?>(null)
    val exportEvent: StateFlow<ExportEvent?> = _exportEvent.asStateFlow()

    private val _drafts = MutableStateFlow<List<Gstr1DraftEntity>>(emptyList())
    val drafts: StateFlow<List<Gstr1DraftEntity>> = _drafts.asStateFlow()

    // ── Available periods ────────────────────────────────────────────────────

    val monthlyPeriods = listOf(
        "April","May","June","July","August","September",
        "October","November","December","January","February","March"
    )
    val quarterlyPeriods = listOf("Apr-Jun","Jul-Sep","Oct-Dec","Jan-Mar")

    val availablePeriods: List<String> get() =
        if (_returnType.value == "Monthly") monthlyPeriods else quarterlyPeriods

    // ── Available FYs ─────────────────────────────────────────────────────────

    val availableFYs: List<String>
        get() {
            val current = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            return (2023..current + 1).map { yr ->
                "$yr-${(yr + 1).toString().takeLast(2)}"
            }.reversed()
        }

    // ─────────────────────────────────────────────────────────────────────────

    init {
        // Load GST profile on start
        viewModelScope.launch {
            val profile = repo.getProfile()
            _gstin.value = profile?.gstin ?: ""
            // Set current FY and period as default
            val cal = java.util.Calendar.getInstance()
            val month = cal.get(java.util.Calendar.MONTH) + 1 // 1-based
            val year  = cal.get(java.util.Calendar.YEAR)
            val fyStartYear = if (month >= 4) year else year - 1
            _financialYear.value = "$fyStartYear-${(fyStartYear + 1).toString().takeLast(2)}"
            _period.value = monthlyPeriods.getOrNull(if (month >= 4) month - 4 else month + 8) ?: "April"
            loadDrafts()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public actions
    // ─────────────────────────────────────────────────────────────────────────

    fun setFinancialYear(fy: String) { _financialYear.value = fy }
    fun setPeriod(period: String)    { _period.value = period }
    fun setReturnType(type: String)  {
        _returnType.value = type
        // Reset period to first of the new type
        _period.value = if (type == "Monthly") monthlyPeriods.first() else quarterlyPeriods.first()
    }

    fun generateReport() {
        val fy = _financialYear.value
        val p  = _period.value
        if (fy.isBlank() || p.isBlank()) {
            _error.value = getApplication<Application>().getString(R.string.gstr1_vm_error_select_fy_period)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Phase 7: GSTR-1 now runs against the server (Phase 6), so it
                // can only see what's already synced. Rather than silently
                // generate a report that's missing whatever's still sitting
                // unsynced on this phone, block and say so plainly — matching
                // the plan's stated default of "clear message over silent gap."
                if (repo.hasPendingSync()) {
                    _error.value = getApplication<Application>().getString(R.string.gstr1_vm_error_unsynced_data)
                    return@launch
                }

                // Same prefs key Gstr2ViewModel already uses successfully for
                // this call ("token", lowercase) — kept identical rather than
                // guessing at an alternate casing.
                val prefs = getApplication<Application>()
                    .getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                val token = prefs.getString("token", "") ?: ""

                val report = try {
                    repo.fetchGstr1Online(
                        token = "Bearer $token",
                        financialYear = fy,
                        period = p,
                        returnType = _returnType.value
                    )
                } catch (e: java.io.IOException) {
                    // Network-layer failure (no connection, timeout, DNS, etc.)
                    // — distinct from a server error, worth a distinct message
                    // per Phase 7's "offline shows a clear state, not a raw
                    // exception" requirement.
                    _error.value = getApplication<Application>().getString(R.string.gstr1_vm_error_server_unreachable)
                    return@launch
                }

                _report.value = report
                _validationResult.value = Gstr1Validator.validate(report)
            } catch (e: Exception) {
                // Was surfacing the raw exception message to the user via
                // Toast (see GstReportsActivity's viewModel1.error collector)
                // — an info-disclosure risk (internal exception text, file
                // paths, etc. shown on screen) as well as unhelpful copy.
                Log.e("Gstr1ViewModel", "Report generation failed", e)
                _error.value = getApplication<Application>().getString(R.string.gstr1_vm_error_generate_failed)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun validateReport() {
        val r = _report.value ?: return
        _validationResult.value = Gstr1Validator.validate(r)
    }

    fun saveDraft() {
        val r = _report.value ?: run {
            _error.value = getApplication<Application>().getString(R.string.gstr1_vm_error_generate_report_first); return
        }
        viewModelScope.launch {
            try {
                repo.saveDraft(r)
                loadDrafts()
                _exportEvent.value = ExportEvent.DraftSaved
            } catch (e: Exception) {
                Log.e("Gstr1ViewModel", "Save draft failed", e)
                _error.value = getApplication<Application>().getString(R.string.gstr1_vm_error_save_draft_failed)
            }
        }
    }

    fun loadDraftById(id: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val r = repo.getDraftById(id)
                if (r != null) {
                    _report.value = r
                    _financialYear.value = r.financialYear
                    _period.value = r.period
                    _returnType.value = r.returnType
                    _validationResult.value = Gstr1Validator.validate(r)
                } else {
                    _error.value = getApplication<Application>().getString(R.string.gstr1_vm_error_draft_not_found)
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteDraft(id: Int) {
        viewModelScope.launch {
            repo.deleteDraft(id)
            loadDrafts()
        }
    }

    fun exportCsv() {
        val r = _report.value ?: run { _error.value = "Generate the report first."; return }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    Gstr1CsvExporter.export(getApplication(), r)
                }
                _exportEvent.value = ExportEvent.CsvExported(result.files, result.directory.absolutePath)
            } catch (e: Exception) {
                Log.e("Gstr1ViewModel", "CSV export failed", e)
                _error.value = getApplication<Application>().getString(R.string.gstr1_vm_error_csv_export_failed)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun exportExcel() {
        val r = _report.value ?: run { _error.value = "Generate the report first."; return }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    Gstr1ExcelExporter.export(getApplication(), r)
                }
                _exportEvent.value = ExportEvent.ExcelExported(result.uri, result.file.absolutePath)
            } catch (e: Exception) {
                Log.e("Gstr1ViewModel", "Excel export failed", e)
                _error.value = getApplication<Application>().getString(R.string.gstr1_vm_error_excel_export_failed)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError()       { _error.value = null }
    fun clearExportEvent() { _exportEvent.value = null }

    private fun loadDrafts() {
        viewModelScope.launch {
            _drafts.value = repo.getDrafts()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Events
    // ─────────────────────────────────────────────────────────────────────────

    sealed class ExportEvent {
        object DraftSaved : ExportEvent()
        data class CsvExported(val files: Map<String, Uri>, val directory: String) : ExportEvent()
        data class ExcelExported(val uri: Uri, val path: String) : ExportEvent()
    }
}
