package com.example.easy_billing.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.easy_billing.R
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
        // Phase 3 fix: Gstr1ViewModel loads the shop's GST profile here and
        // uses it to fill `gstin` on the generated report; this ViewModel
        // declared the same `_gstin` StateFlow but never populated it, so
        // Gstr2Repository.fetchGstr2() always returned gstin = "" and
        // Gstr2Validator flagged every single report with a permanent false
        // "Store GSTIN is invalid or missing" error. Mirror Gstr1's pattern.
        viewModelScope.launch {
            val profile = db.gstProfileDao().get()
            _gstin.value = profile?.gstin ?: ""
        }
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
            _error.value = getApplication<Application>().getString(R.string.gstr2_vm_error_select_fy_period)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // GSTR-2 is computed on the server from synced data, so anything
                // still on this phone would be silently missing from the report —
                // under-claiming input tax credit. Block with a clear message,
                // matching what GSTR-1 already does. Covers unsynced purchases,
                // purchase returns/notes, and purchase cancellations.
                val pending = withContext(Dispatchers.IO) {
                    db.purchaseDao().countUnsynced() > 0 ||
                        db.purchaseReturnDao().countUnsynced() > 0 ||
                        db.purchaseDao().getCancelledUnsynced().isNotEmpty()
                }
                if (pending) {
                    _error.value = getApplication<Application>().getString(R.string.gstr2_vm_error_unsynced_data)
                    return@launch
                }

                // In a real app we would map `fy` and `p` to `startDate` and `endDate` string formats
                // Here we assume backend expects YYYY-MM-DD
                val (startDate, endDate) = resolveDates(fy, p, _returnType.value)
                
                val prefs = getApplication<Application>().getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                val token = prefs.getString("token", "") ?: ""
                val report  = repo.fetchGstr2("Bearer $token", startDate, endDate)

                _report.value = report.copy(
                    gstin = _gstin.value,
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

        val isNextYear = listOf("January", "February", "March", "Jan-Mar").contains(period)
        val y1 = if (isNextYear) endYear else startYear
        val y2 = if (isNextYear) endYear else startYear

        // Phase 2 fix: February used to be hardcoded "02-01".."02-28",
        // silently dropping Feb 29 on a leap year (comment used to say
        // "ignoring leap year for simplicity"). Derive the real last day of
        // the month from Calendar instead, same approach GSTR-1's
        // periodRange() already used correctly (getActualMaximum).
        fun lastDayOfMonth(year: Int, month1Based: Int): Int {
            val cal = java.util.Calendar.getInstance()
            cal.set(year, month1Based - 1, 1)
            return cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        }

        val monthNumbers = mapOf(
            "April" to 4, "May" to 5, "June" to 6, "July" to 7,
            "August" to 8, "September" to 9, "October" to 10,
            "November" to 11, "December" to 12,
            "January" to 1, "February" to 2, "March" to 3
        )

        val quarterMap = mapOf(
            "Apr-Jun" to Pair(Pair(4, 1), Pair(6, 30)),
            "Jul-Sep" to Pair(Pair(7, 1), Pair(9, 30)),
            "Oct-Dec" to Pair(Pair(10, 1), Pair(12, 31)),
            "Jan-Mar" to Pair(Pair(1, 1), Pair(3, 31))
        )

        val startDate: String
        val endDate: String
        if (returnType == "Monthly") {
            val m = monthNumbers[period]!!
            val lastDay = lastDayOfMonth(y2, m)
            startDate = "$y1-${"%02d".format(m)}-01"
            endDate   = "$y2-${"%02d".format(m)}-${"%02d".format(lastDay)}"
        } else {
            val (from, to) = quarterMap[period]!!
            startDate = "$y1-${"%02d".format(from.first)}-${"%02d".format(from.second)}"
            endDate   = "$y2-${"%02d".format(to.first)}-${"%02d".format(to.second)}"
        }
        return Pair(startDate, endDate)
    }

    fun saveDraft() {
        val r = _report.value ?: run {
            _error.value = getApplication<Application>().getString(R.string.gstr2_vm_error_generate_report_first); return
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

    fun loadDraft(draft: Gstr2DraftEntity) {
        // The row already hands us the full entity (unlike GSTR-1, which only
        // passes an id and re-queries) — the JSON blob is right there, so this
        // is a synchronous parse, no repo round-trip needed.
        _isLoading.value = true
        try {
            val r = Gstr2Report.fromJson(draft.reportJson)
            _report.value = r
            _financialYear.value = draft.financialYear
            _period.value = draft.period
            _returnType.value = draft.returnType
            _gstin.value = draft.gstin
        } catch (e: Exception) {
            _error.value = "Failed to open draft: ${e.message}"
        } finally {
            _isLoading.value = false
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
