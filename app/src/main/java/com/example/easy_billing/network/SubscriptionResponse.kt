package com.example.easy_billing.network

data class SubscriptionResponse(
    val plan: String?,
    // base | premium | null (no subscription at all). Read by
    // SubscriptionTierCache and the tier-gated Dashboard entry points —
    // never inferred from `plan` string-matching (see onboarding/
    // subscription plan §5.5). Matches backend
    // subscription_routes.get_subscription()'s "tier" field, added
    // alongside it.
    val tier: String? = null,
    val expiry_date: String?,
    // UTC instant (epoch-millis) from the backend; render in the shop timezone.
    // Preferred over expiry_date, which is a bare UTC string the old code
    // mis-rendered as device-local (off-by-a-day near midnight).
    val expiry_ms: Long? = null,
    val remaining_days: Int,
    val status: String,
    // Drives whether SubscriptionActivity shows the "Start free trial"
    // card — server-side truth (Shop.has_used_trial), never inferred or
    // cached locally, since it must not be resettable by clearing app
    // data (plan §4.3).
    val has_used_trial: Boolean = false
)