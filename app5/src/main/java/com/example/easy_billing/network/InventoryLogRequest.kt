package com.example.easy_billing.network

data class InventoryLogRequest(
    val product_id: Int,
    val type: String,
    val quantity: Double,
    val price: Double,
    val date: Long,
    // Stable idempotency key ("<device_id>:<local_log_id>") so a retried push
    // can't create a duplicate inventory log on the server (Sync audit S2).
    val client_uid: String? = null
)