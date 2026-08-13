package com.example.easy_billing.util

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.network.DiagnosticReportUploadRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.UserEventDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one-shot "Send diagnostic report" action — see UserEventLog.kt and
 * UserEventLogger.kt for the full rationale.
 *
 * Deliberately NOT a share-sheet flow: the shop owner never sees the file.
 * A single tap silently uploads the ENTIRE local event log (every screen
 * open, every Save/Cancel/Confirm/Delete tap, every validation failure,
 * every error — not just the low-volume subset that syncs automatically)
 * straight to the backend's short-retention `diagnostic_reports` table,
 * then shows a plain confirmation toast with no content from the log
 * itself. Support then pulls it via GET /admin/diagnostic-reports.
 *
 * Call [upload] from a coroutine scope tied to the triggering screen
 * (e.g. lifecycleScope) — it's a single network call, not fire-and-forget
 * like UserEventLogger, since the caller needs to show success/failure.
 */
object DiagnosticReportUploader {

    private const val TAG = "DiagnosticReportUpload"

    suspend fun upload(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("auth", Context.MODE_PRIVATE)
        val token = prefs.getString("TOKEN", null)
        if (token == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, R.string.diagnostic_report_no_session, Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val rows = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(appContext).userEventLogDao().getAllOrderedByTime()
            }
            val events = rows.map { row ->
                UserEventDto(
                    event_type = row.eventType,
                    screen = row.screen,
                    detail = row.detail,
                    created_at = row.createdAt,
                )
            }

            val response = RetrofitClient.api.uploadDiagnosticReport(
                token, DiagnosticReportUploadRequest(events)
            )
            Log.d(TAG, "Diagnostic report uploaded: report_id=${response.report_id} event_count=${response.event_count}")

            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, R.string.diagnostic_report_sent, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // This one genuinely should surface a failure — the whole
            // point is the shop owner knows whether it went through, but
            // still no raw exception text, same discipline as everywhere
            // else in this trail.
            Log.e(TAG, "Diagnostic report upload failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, R.string.diagnostic_report_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Convenience for call sites that just want to fire this from a click listener. */
    fun uploadAsync(scope: CoroutineScope, context: Context) {
        scope.launch { upload(context) }
    }
}
