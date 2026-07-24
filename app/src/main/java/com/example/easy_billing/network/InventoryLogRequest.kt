package com.example.easy_billing.network

data class InventoryLogRequest(
    val product_id: Int,
    val type: String,
    val quantity: Double,
    val price: Double,
    val date: Long,
    // Stable idempotency key ("<device_id>:<local_log_id>") so a retried push
    // can't create a duplicate inventory log on the server (Sync audit S2).
    val client_uid: String? = null,
    // Avg-cost audit, Fix 2: the average cost this device already computed
    // for this event, once, using the app's one weighted-average formula.
    // The backend stores this directly instead of re-deriving average cost
    // with its own separate copy of the same math — removing the
    // possibility of client/server formula drift entirely.
    val resulting_average_cost: Double = 0.0
)