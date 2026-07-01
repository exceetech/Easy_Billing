package com.example.easy_billing.network

/**
 * Response shape for the purchase-return and scrap batch-sync endpoints.
 *
 * `record_id_map` echoes only the local_ids the server actually ACCEPTED
 * ("local_id" → server_id). The client must mark only these synced and leave
 * the rest pending for retry — never blanket-mark the whole batch (Sync re-audit
 * R1). The previous code declared these calls as returning PurchaseSyncResponse
 * (which has purchase_id_map/item_id_map, not record_id_map), so the accepted
 * set was invisible.
 */
data class RecordSyncResponse(
    val success_count: Int = 0,
    val record_id_map: Map<String, Int> = emptyMap(),
    val message: String? = null
)
