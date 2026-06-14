package com.example.easy_billing.network

data class BillResponse(
    val bill_id: Int,
    val bill_number: String,
    val total_amount: Double,
    val gst: Double,
    val discount: Double,
    val payment_method: String,
    val created_at: String,
    // GST snapshot fields — nullable so older backend responses still parse.
    val invoice_type: String? = null,
    val customer_state: String? = null,
    val customer_state_code: String? = null,
    val supply_type: String? = null,

    // N1: voided (cancelled) bills stay visible in history with a badge.
    // Default false so older backend responses still parse.
    val is_cancelled: Boolean = false
)