package com.example.easy_billing.network

// Mirrors backend app/schemas/subscription_payment_schema.py exactly —
// keep both in sync if either changes.

data class PlanResponse(
    val plan_code: String,
    val name: String,
    val tier: String,
    val price_paise: Int,
    val duration_days: Int
)

data class ValidateCouponRequest(
    val plan_code: String,
    val coupon_code: String
)

data class ValidateCouponResponse(
    val valid: Boolean,
    val original_amount_paise: Int,
    val discount_amount_paise: Int,
    // Plan price minus coupon discount, before service charge/GST.
    val subtotal_after_discount_paise: Int,
    val service_charge_paise: Int,
    val gst_paise: Int,
    // The true charged amount — subtotal + service charge + GST. Always
    // display this as "Total", never original/discount alone.
    val final_amount_paise: Int
)

data class CreateOrderRequest(
    val plan_code: String,
    val coupon_code: String? = null
)

data class CreateOrderResponse(
    val order_db_id: Int,
    val razorpay_order_id: String,
    val razorpay_key_id: String,
    val amount_paise: Int,
    val subtotal_after_discount_paise: Int,
    val service_charge_paise: Int,
    val gst_paise: Int,
    val currency: String,
    // True when a 100%-off coupon reduced the price to zero — the
    // backend already activated the subscription directly in this case
    // (Razorpay's checkout does not support a ₹0 charge), so the app
    // must skip Checkout.open() entirely and go straight to the success
    // state. See SubscriptionActivity.onPayClicked().
    val is_free: Boolean
)

data class VerifyPaymentRequest(
    val order_db_id: Int,
    val razorpay_order_id: String,
    val razorpay_payment_id: String,
    val razorpay_signature: String
)

data class SubscriptionActionResponse(
    val success: Boolean,
    val status: String,
    val tier: String?,
    val plan: String?,
    val expiry_date: String?
)
