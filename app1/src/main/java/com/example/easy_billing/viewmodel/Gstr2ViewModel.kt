package com.example.easy_billing.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.gstr2.Gstr2DraftEntity
import com.example.easy_billing.gstr2.Gstr2Report

import com.example.easy_billing.gstr2.Gstr2CsvExporter
import com.example.easy_billing.gstr2.Gstr2ExcelExporter
import com.example.easy_billing.gstr2.Gstr2Repository
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Gstr2ViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getDatabase(app)
    private val repo = Gstr2Repository(RetrofitClient.api, db.gstr2DraftDao())

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

    private val _report = MutableStateFlow<Gstr2Report?>(null)
    val report: StateFlow<Gstr2Report?> = _report.asStateFlow()

    // ── UI state ──────────────────────────────────────────────────────────────

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _exportEvent = MutableStateFlow<ExportEvent?>(null)
    val exportEvent: StateFlow<ExportEvent?> = _exportEvent.asStateFlow()

    private val _drafts = MutableStateFlow<List<Gstr2DraftEntity>>(emptyList())
    val drafts: StateFlow<List<Gstr2DraftEntity>> = _drafts.asStateFlow()

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
        // Set current FY and period as default
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH) + 1 // 1-based
        val year  = cal.get(java.util.Calendar.YEAR)
        val fyStartYear = if (month >= 4) year else year - 1
        _financialYear.value = "$fyStartYear-${(fyStartYear + 1).toString().takeLast(2)}"
        _period.value = monthlyPeriods.getOrNull(if (month >= 4) month - 4 else month + 8) ?: "April"
        loadDrafts()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public actions
    // ─────────────────────────────────────────────────────────────────────────

    fun setFinancialYear(fy: String) { _financialYear.value = fy }
    fun setPeriod(period: String)    { _period.value = period }
    fun setReturnType(type: String)  {
        _returnType.value = type
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
                // In a real app we would map `fy` and `p` to `startDate` and `endDate` string formats
                // Here we assume backend expects YYYY-MM-DD
                val (startDate, endDate) = resolveDates(fy, p, _returnType.value)
                
                val prefs = getApplication<Application>().getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                val token = prefs.getString("token", "") ?: ""
                val report  = repo.fetchGstr2("Bearer $token", startDate, endDate)

                _report.value = report.copy(
                    financialYear = fy,
                    period = p,
                    returnType = _returnType.value
                )
            } catch (e: Exception) {
                _error.value = "Failed to generate report: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun resolveDates(fy: String, period: String, returnType: String): Pair<String, String> {
        val startYear = fy.substringBefore("-").toInt()
        val endYear = startYear + 1

        val monthMap = mapOf(
            "April" to Pair("04-01", "04-30"),
            "May" to Pair("05-01", "05-31"),
            "June" to Pair("06-01", "06-30"),
            "July" to Pair("07-01", "07-31"),
            "August" to Pair("08-01", "08-31"),
            "September" to Pair("09-01", "09-30"),
            "October" to Pair("10-01", "10-31"),
            "November" to Pair("11-01", "11-30"),
            "December" to Pair("12-01", "12-31"),
            "January" to Pair("01-01", "01-31"),
            "February" to Pair("02-01", "02-28"), // Ignoring leap year for simplicity
            "March" to Pair("03-01", "03-31")
        )
        
        val quarterMap = mapOf(
            "Apr-Jun" to Pair("04-01", "06-30"),
            "Jul-Sep" to Pair("07-01", "09-30"),
            "Oct-Dec" to Pair("10-01", "12-31"),
            "Jan-Mar" to Pair("01-01", "03-31")
        )

        val isNextYear = listOf("January", "February", "March", "Jan-Mar").contains(period)
        val y1 = if (isNextYear) endYear else startYear
        val y2 = if (isNextYear) endYear else startYear

        val dates = if (returnType == "Monthly") monthMap[period]!! else quarterMap[period]!!
        val startDate = "$y1-${dates.first}"
        val endDate = "$y2-${dates.second}"
        return Pair(startDate, endDate)
    }

    fun saveDraft() {
        val r = _report.value ?: run {
            _error.value = "Generate the report first."; return
        }
        viewModelScope.launch {
            try {
                val entity = Gstr2DraftEntity(
                    gstin = r.gstin,
                    financialYear = r.financialYear,
                    period = r.period,
                    returnType = r.returnType,
                    reportJson = r.toJson()
                )
                repo.saveDraft(entity)
                loadDrafts()
                _exportEvent.value = ExportEvent.DraftSaved
            } catch (e: Exception) {
                _error.value = "Failed to save draft: ${e.message}"
            }
        }
    }

    fun deleteDraft(draft: Gstr2DraftEntity) {
        viewModelScope.launch {
            repo.deleteDraft(draft)
            loadDrafts()
        }
    }

    
    fun exportCsv() {
        val r = _report.value ?: run {
            _error.value = "Generate the report first."
            return
        }
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val result = Gstr2CsvExporter.export(context, r)
                _exportEvent.value = ExportEvent.CsvExported(result.files, result.directory.absolutePath)
            } catch (e: Exception) {
                _error.value = "CSV Export failed: ${e.message}"
            }
        }
    }

    fun exportExcel() {
        val r = _report.value ?: run {
            _error.value = "Generate the report first."
            return
        }
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val result = Gstr2ExcelExporter.export(context, r)
                _exportEvent.value = ExportEvent.ExcelExported(result.uri, result.file.absolutePath)
            } catch (e: Exception) {
                _error.value = "Excel Export failed: ${e.message}"
            }
        }
    }

    fun clearError()       { _error.value = null }
    fun clearExportEvent() { _exportEvent.value = null }

    private fun loadDrafts() {
        viewModelScope.launch {
            repo.getAllDrafts().collect { draftsList ->
                _drafts.value = draftsList
            }
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
