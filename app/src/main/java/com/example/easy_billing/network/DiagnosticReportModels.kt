package com.example.easy_billing.network

/**
 * One-shot, on-demand full local event-log upload — see
 * util/DiagnosticReportUploader.kt. Reuses [UserEventDto] since a
 * diagnostic report is just "every row currently in the local table,"
 * not a different event shape.
 */
data class DiagnosticReportUploadRequest(
    val events: List<UserEventDto>
)

data class DiagnosticReportUploadResponse(
    val report_id: Int = 0,
    val event_count: Int = 0,
    val message: String? = null
)
