package com.example.easy_billing.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
            _error.value = "Please select Financial Year and Period."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val rawData = repo.fetchForPeriod(fy, p)
                val report  = Gstr1Generator.generate(rawData, fy, p, _returnType.value)
                _report.value = report
                _validationResult.value = Gstr1Validator.validate(report)
            } catch (e: Exception) {
                _error.value = "Failed to generate report: ${e.message}"
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
            _error.value = "Generate the report first."; return
        }
        viewModelScope.launch {
            try {
                repo.saveDraft(r)
                loadDrafts()
                _exportEvent.value = ExportEvent.DraftSaved
            } catch (e: Exception) {
                _error.value = "Failed to save draft: ${e.message}"
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
                    _error.value = "Draft not found."
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
                _error.value = "CSV export failed: ${e.message}"
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
                _error.value = "Excel export failed: ${e.message}"
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
