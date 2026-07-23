package com.example.easy_billing.network

// ================= REQUEST =================
data class CreateSaleRequest(
    val items: List<SaleItemDto>,
    val bill_number: String? = null,
    // Idempotency key (Report 5 fix) — same local bill id + device id the
    // app already sends to /bills/create, so a retried/backfilled push of
    // the same sale is recognized instead of duplicating profit rows.
    val client_bill_id: Int? = null,
    val client_device_id: String? = null
)

// ================= ITEM =================
data class SaleItemDto(
    val product_id: Int,
    val quantity: Double,
    val selling_price: Double,
    val cost_price: Double,

    // 🔥 REQUIRED for backend grouping
    val product_name: String,
    val variant: String?
)