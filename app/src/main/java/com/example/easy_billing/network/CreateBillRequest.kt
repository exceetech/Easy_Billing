package com.example.easy_billing.network

data class CreateBillRequest(
    val bill_number: String,
    val items: List<BillItemRequest>,
    val payment_method: String = "Cash",
    val discount: Double = 0.0,
    val gst: Double = 0.0,
    val total_amount: Double = 0.0,
    
    val subtotal: Double = 0.0,
    val discount_amount: Double = 0.0,
    val taxable_amount: Double = 0.0,
    
    val cgst_amount: Double = 0.0,
    val sgst_amount: Double = 0.0,
    val igst_amount: Double = 0.0,
    val cess_amount: Double = 0.0,
    val gst_amount: Double = 0.0,
    
    val round_off: Double = 0.0,
    val final_amount: Double = 0.0,
    
    val gst_scheme: String = "Regular",
    val supply_type: String = "intrastate",
    val customer_state: String? = null,
    val customer_state_code: String? = null,
    val invoice_type: String = "B2C",
    val is_gst_invoice: Boolean = false,

    // Idempotency key: local Room bill id + device id. The backend
    // refuses to create a second row for the same key, so retried or
    // concurrent syncs can never duplicate a bill.
    val client_bill_id: Int? = null,
    val client_device_id: String? = null,

    val created_at: String? = null,

    // Cancellation (void) state — covers bills cancelled on the device
    // BEFORE their first sync, so they never count in server reports.
    val is_cancelled: Boolean = false,
    val cancelled_at: Long? = null   // epoch millis
)

/**
 * Voids an already-synced bill on the server (sets active=false so all
 * reports exclude it). Identified by bill_number or, when the number is
 * not yet known, by the idempotency pair (client_device_id, client_bill_id).
 */
data class CancelBillRequest(
    val bill_number: String? = null,
    val client_bill_id: Int? = null,
    val client_device_id: String? = null,
    val cancelled_at: Long? = null   // epoch millis
)

/**
 * Voids a purchase on the server. Same idempotency shape as [CancelBillRequest]:
 * resolved by server id when known, else by (client_device_id, client_purchase_id).
 */
data class CancelPurchaseRequest(
    val invoice_number: String? = null,
    val client_purchase_id: Int? = null,
    val server_purchase_id: Int? = null,
    val client_device_id: String? = null,
    val cancelled_at: Long? = null   // epoch millis
)

data class BillItemRequest(
    val shop_product_id: Int,
    val product_name: String? = null,
    val quantity: Double,
    val variant: String? = null,
    val unit: String? = "unit",
    
    val unit_price: Double = 0.0,
    val line_subtotal: Double = 0.0,
    val discount_amount: Double = 0.0,
    val taxable_amount: Double = 0.0,
    
    val gst_rate: Double = 0.0,
    val cgst_rate: Double = 0.0,
    val sgst_rate: Double = 0.0,
    val igst_rate: Double = 0.0,
    
    val cgst_amount: Double = 0.0,
    val sgst_amount: Double = 0.0,
    val igst_amount: Double = 0.0,
    val cess_amount: Double = 0.0,
    
    val total_amount: Double = 0.0,
    val hsn_code: String = ""
)