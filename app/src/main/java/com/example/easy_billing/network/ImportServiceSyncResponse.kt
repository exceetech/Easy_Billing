package com.example.easy_billing.network

/**
 * Response from POST /import_services/sync.
 *
 * `accepted_local_ids` lists the local_ids the server actually persisted, so the
 * client marks ONLY those rows synced instead of blanket-marking the whole batch
 * on HTTP 200 (M1). Defaults keep it tolerant of older server builds that only
 * returned `{ "message": ... }`.
 *
 * `rejected` lists rows the server refused because they break a GSTR-2 rule.
 * They are skipped rather than failing the batch, and the client parks them as
 * "rejected" so they stop being retried on every sync forever.
 */
data class ImportServiceSyncResponse(
    val message: String = "",
    val accepted_local_ids: List<Int> = emptyList(),
    val rejected: List<RejectedImportService> = emptyList()
)

data class RejectedImportService(
    val local_id: Int? = null,
    val invoice_number: String = "",
    val reason: String = ""
)
