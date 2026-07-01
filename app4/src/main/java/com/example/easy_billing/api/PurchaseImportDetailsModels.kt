package com.example.easy_billing.api

data class PurchaseImportDetailsDto(
    val local_id: Int,
    val purchase_id: Int?,
    val local_purchase_id: Int,
    val port_code: String,
    val bill_of_entry_number: String,
    val bill_of_entry_date: Long,
    val bill_of_entry_value: Double,
    val document_type: String,
    val sez_supplier_gstin: String?,
    val sync_status: String,
    val device_id: String,
    val created_at: Long,
    val updated_at: Long
)

data class PurchaseImportDetailsSyncRequest(
    val records: List<PurchaseImportDetailsDto>
)

data class PurchaseImportDetailsSyncResponse(
    val success_count: Int,
    val record_id_map: Map<String, Int>, // local_id -> server_id (if backend generates an ID, otherwise unused)
    val failed: List<String>,
    val message: String
)
