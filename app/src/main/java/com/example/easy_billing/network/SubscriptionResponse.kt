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
    // Kept for display/analytics only — do NOT use this alone to decide
    // whether to show the "Start free trial" card. Use
    // is_trial_offerable below instead (it's what actually accounts for
    // an active Base/Premium subscription blocking a trial too).
    val has_used_trial: Boolean = false,
    // Single source of truth for whether the trial card/button should
    // be shown — computed server-side by
    // subscription_entitlement_service.is_trial_offerable(), which
    // returns false whenever the shop already has a live active_base or
    // active_premium subscription, not just when has_used_trial is true.
    // This is what fixes the trial card showing for a shop already on a
    // paid Base plan.
    val is_trial_offerable: Boolean = false
)