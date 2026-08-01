package com.example.easy_billing.network

data class ProfileResponse(
    val shop_name: String,
    val owner_name: String,
    val email: String,
    val status: String,
    // Onboarding routing-gate fields (see BaseActivity/SplashActivity/
    // MainActivity/ChangePasswordActivity onboarding checks, and
    // OnboardingActivity which reads the per-step flags to resume at the
    // right step after an interruption). null/false until each step's
    // own save action completes.
    val onboarding_completed_at: String? = null,
    val onboarding_subscription_done: Boolean = false,
    val onboarding_shop_info_done: Boolean = false,
    val onboarding_billing_done: Boolean = false,
    val onboarding_terms_done: Boolean = false,
    // Server-side kill switch (plan §6.6) — every routing gate must
    // check this is true before redirecting to OnboardingActivity.
    // Defaults to true so an old cached/mocked response (or a network
    // hiccup path that doesn't reach this field) doesn't accidentally
    // disable enforcement — the safe default is "enforcement on".
    val onboarding_enforcement_enabled: Boolean = true
)