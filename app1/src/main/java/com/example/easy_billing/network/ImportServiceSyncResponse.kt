package com.example.easy_billing.network

/**
 * Response from POST /import_services/sync/{shop_id}.
 *
 * `accepted_local_ids` lists the local_ids the server actually persisted, so the
 * client marks ONLY those rows synced instead of blanket-marking the whole batch
 * on HTTP 200 (M1). Defaults keep it tolerant of older server builds that only
 * returned `{ "message": ... }`.
 */
data class ImportServiceSyncResponse(
    val message: String = "",
    val accepted_local_ids: List<Int> = emptyList()
)
