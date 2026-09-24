package com.example.easy_billing

import com.example.easy_billing.R

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.*
import com.razorpay.Checkout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Subscription plan picker — status card, trial offer, billing-cycle
 * chips, and plan cards. Selecting a plan and tapping Continue hands off
 * to ConfirmPaymentActivity, which owns the coupon entry, Razorpay
 * checkout, and server-side payment verification (trust boundary lives
 * there now, not here — see that class's doc comment).
 */
class SubscriptionActivity : BaseActivity() {

    private lateinit var cardStatus: LinearLayout
    private lateinit var statusHeader: LinearLayout
    private lateinit var statusIconBadge: FrameLayout
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvPlan: TextView
    private lateinit var tvExpiry: TextView
    private lateinit var tvDaysLeft: TextView
    private lateinit var tvStatus: TextView

    // Below this many remaining days, an otherwise-active plan switches
    // the status card to the amber "ending soon" state instead of teal.
    private val endingSoonThresholdDays = 7

    private lateinit var cardTrial: LinearLayout
    private lateinit var btnStartTrial: Button

    private lateinit var llBillingCycle: LinearLayout
    private lateinit var llPlans: LinearLayout
    private lateinit var btnContinue: com.google.android.material.button.MaterialButton

    private var plans: List<PlanResponse> = emptyList()
    private var selectedPlan: PlanResponse? = null
    private var planCardViews: MutableMap<String, LinearLayout> = mutableMapOf()
    private var planRadioViews: MutableMap<String, FrameLayout> = mutableMapOf()
    private var planRadioDotViews: MutableMap<String, View> = mutableMapOf()
    
    // Which duration_days bucket is currently shown (30/90/195/395 —
    // 1/3/6/12 months). Drives both the chip row's selected state and
    // which two plan cards (Base + Premium) are visible at a time,
    // instead of listing all eight plans in one long scroll.
    private var selectedCycleDays: Int = 30
    private var cycleChipViews: MutableMap<Int, TextView> = mutableMapOf()

    // Current subscription snapshot from the last loadSubscription() call —
    // used only to give an immediate, friendly explanation when tapping
    // Continue on a Base plan while already on a paid Premium period,
    // instead of letting the user go through the whole payment popup only
    // to be rejected by create-order's downgrade block at the very end.
    // The backend remains the actual source of truth/enforcement here —
    // this is purely a same-explanation-earlier UX shortcut.
    private var currentTier: String? = null
    private var currentStatus: String? = null
    private var currentExpiryLabel: String? = null
    private var currentRemainingDays: Int = 0

    // Launches ConfirmPaymentActivity and, on RESULT_OK (payment verified
    // there), finishes this screen too — mirrors the auto-return pattern
    // used elsewhere (StoreSettings/BillingSettings) during onboarding.
    private val confirmPaymentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            loadSubscription()
            if (result.resultCode == Activity.RESULT_OK) {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)
        com.example.easy_billing.util.UserEventLogger.logAction("Subscription", "opened")

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        // Preloading here (instead of only in ConfirmPaymentActivity) keeps
        // the Razorpay SDK warm by the time the user reaches checkout.
        Checkout.preload(applicationContext)

        cardStatus = findViewById(R.id.cardStatus)
        statusHeader = findViewById(R.id.statusHeader)
        statusIconBadge = findViewById(R.id.statusIconBadge)
        ivStatusIcon = findViewById(R.id.ivStatusIcon)
        tvPlan = findViewById(R.id.tvPlan)
        tvExpiry = findViewById(R.id.tvExpiry)
        tvDaysLeft = findViewById(R.id.tvDaysLeft)
        tvStatus = findViewById(R.id.tvStatus)

        cardTrial = findViewById(R.id.cardTrial)
        btnStartTrial = findViewById(R.id.btnStartTrial)

        llBillingCycle = findViewById(R.id.llBillingCycle)
        llPlans = findViewById(R.id.llPlans)
        btnContinue = findViewById(R.id.btnContinue)

        btnStartTrial.setOnClickListener { onStartTrialClicked() }
        btnContinue.setOnClickListener { onContinueClicked() }

        loadSubscription()
        loadPlans()
    }

    override fun onResume() {
        super.onResume()
        loadSubscription()
    }

    // ================= CURRENT SUBSCRIPTION STATUS =================

    private fun loadSubscription() {
        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                Toast.makeText(this@SubscriptionActivity, R.string.not_logged_in, Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                val res = RetrofitClient.api.getSubscription(token)

                // No tier at all means the shop has never had a plan (or a
                // past one lapsed with nothing on file) — this is a
                // distinct "no plan yet" state, not the same as "Expired"
                // (which implies there WAS a plan with a real expiry date).
                val hasPlan = res.tier != null

                currentTier = res.tier
                currentStatus = res.status
                currentRemainingDays = res.remaining_days
                currentExpiryLabel = when {
                    res.expiry_ms != null ->
                        com.example.easy_billing.util.AppTime.formatter("dd MMM yyyy").format(java.util.Date(res.expiry_ms))
                    res.expiry_date != null -> formatDate(res.expiry_date)
                    else -> null
                }

                // One plain headline + one supporting line (was: plan name,
                // status pill, expiry and days-left as four separate lines).
                val daysLine = if (hasPlan) {
                    if (currentExpiryLabel != null)
                        getString(R.string.sub_valid_until, currentExpiryLabel, res.remaining_days)
                    else getString(R.string.sub_days_left, res.remaining_days)
                } else getString(R.string.sub_choose_plan_below)

                // "trial" is a genuinely usable, active status — must not
                // fall into the same visual bucket as "expired" the way a
                // naive `if (status == "active")` check would (see the
                // backend fix in dependencies.get_current_shop for the
                // same class of bug on the enforcement side).
                val statusLabel: String
                val headline: String
                val cardState: StatusCardState
                when {
                    !hasPlan -> {
                        statusLabel = getString(R.string.sub_st_get_started)
                        headline = getString(R.string.sub_head_none)
                        cardState = StatusCardState.NO_PLAN
                    }
                    res.status == "trial" -> {
                        statusLabel = getString(R.string.sub_st_trial)
                        headline = getString(R.string.sub_head_trial)
                        cardState = StatusCardState.TRIAL
                    }
                    res.status == "active" && res.remaining_days <= endingSoonThresholdDays -> {
                        statusLabel = getString(R.string.sub_st_ending)
                        headline = getString(R.string.sub_head_ending)
                        cardState = StatusCardState.ENDING_SOON
                    }
                    res.status == "active" -> {
                        statusLabel = getString(R.string.sub_st_active)
                        headline = getString(R.string.sub_head_active)
                        cardState = StatusCardState.ACTIVE
                    }
                    else -> {
                        statusLabel = getString(R.string.sub_st_expired)
                        headline = getString(R.string.sub_head_expired)
                        cardState = StatusCardState.EXPIRED
                    }
                }
                tvStatus.text = statusLabel
                tvPlan.text = headline
                tvExpiry.text = daysLine
                applyStatusCardState(cardState)

                // Trial card visibility now comes straight from the
                // server's is_trial_offerable — computed by
                // subscription_entitlement_service against the shop's
                // real current state, not just has_used_trial. Fixes the
                // trial card showing while the shop is already on a paid
                // Base subscription (it used to only check tier !=
                // "premium", which let Base slip through).
                cardTrial.visibility = if (res.is_trial_offerable) View.VISIBLE else View.GONE

            } catch (e: Exception) {
                e.printStackTrace()
                com.example.easy_billing.util.UserEventLogger.logError(
                    "Subscription", "load_subscription_failed: ${e.javaClass.simpleName}"
                )
                com.google.android.material.snackbar.Snackbar.make(
                    tvPlan,
                    getString(R.string.subscriptionactivity_failed_to_load),
                    com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
                ).setAction(R.string.retry) { loadSubscription() }.show()
            }
        }
    }

    /** Color states the shared status-card template can render as — one
     * bordered-tint layout, palettes swapped at runtime. EXPIRED is its
     * own red/danger look (distinct from the amber "needs attention"
     * look) since a lapsed subscription is more severe than one merely
     * ending soon or never started. */
    private enum class StatusCardState { ACTIVE, ENDING_SOON, NO_PLAN, TRIAL, EXPIRED }

    private fun applyStatusCardState(state: StatusCardState) {
        // Only two looks: calm green (active / trial) and amber (needs
        // attention). The icon still follows the real state. The card
        // itself is now a white bg_inv_card_cream shell (set in XML) with
        // a tinted header banner + step-circle badge + status pill, in
        // the same header-banner language as the Security/Data/Diagnose
        // cards on Data & Security — only the header/pill/chip tints and
        // the icon swap here; tvPlan's headline stays dark on the white
        // body regardless of state.
        val look = when (state) {
            StatusCardState.ACTIVE, StatusCardState.TRIAL -> StatusCardState.ACTIVE
            StatusCardState.ENDING_SOON, StatusCardState.NO_PLAN -> StatusCardState.ENDING_SOON
            StatusCardState.EXPIRED -> StatusCardState.EXPIRED
        }
        val headerBg: Int
        val ringBg: Int
        val pillBg: Int
        val pillText: Int
        val expiryText: Int
        val headlineText: Int
        val icon: Int

        when (look) {
            StatusCardState.ACTIVE -> {
                headerBg = R.drawable.bg_inv_customer_header
                ringBg = R.drawable.bg_step_circle_ring
                pillBg = R.drawable.bg_pill_green
                pillText = 0xFF16A34A.toInt()
                expiryText = 0xFF5C6B65.toInt()
                headlineText = 0xFF1A1A18.toInt()
            }
            StatusCardState.ENDING_SOON -> {
                headerBg = R.drawable.bg_status_header_amber
                ringBg = R.drawable.bg_step_circle_ring_status_amber
                pillBg = R.drawable.bg_pill_amber
                pillText = 0xFF8A6526.toInt()
                expiryText = 0xFF8A6526.toInt()
                headlineText = 0xFF1A1A18.toInt()
            }
            StatusCardState.TRIAL -> { /* unused: mapped to ACTIVE */
                headerBg = R.drawable.bg_inv_customer_header
                ringBg = R.drawable.bg_step_circle_ring
                pillBg = R.drawable.bg_pill_green
                pillText = 0xFF16A34A.toInt()
                expiryText = 0xFF5C6B65.toInt()
                headlineText = 0xFF1A1A18.toInt()
            }
            StatusCardState.NO_PLAN -> { /* unused: mapped to ENDING_SOON */
                headerBg = R.drawable.bg_status_header_amber
                ringBg = R.drawable.bg_step_circle_ring_status_amber
                pillBg = R.drawable.bg_pill_amber
                pillText = 0xFF8A6526.toInt()
                expiryText = 0xFF8A6526.toInt()
                headlineText = 0xFF1A1A18.toInt()
            }
            StatusCardState.EXPIRED -> {
                headerBg = R.drawable.bg_inv_danger_header
                ringBg = R.drawable.bg_step_circle_ring_red
                pillBg = R.drawable.bg_pill_danger_outline
                pillText = 0xFFA32D2D.toInt()
                expiryText = 0xFF791F1F.toInt()
                headlineText = 0xFF791F1F.toInt()
            }
        }

        icon = when (state) {
            StatusCardState.ACTIVE -> R.drawable.ic_lucide_badge_check
            StatusCardState.ENDING_SOON -> R.drawable.ic_lc_clock
            StatusCardState.NO_PLAN -> R.drawable.ic_lucide_alert
            StatusCardState.TRIAL -> R.drawable.ic_lucide_sparkles
            StatusCardState.EXPIRED -> R.drawable.ic_lucide_alert
        }

        statusHeader.setBackgroundResource(headerBg)
        statusIconBadge.setBackgroundResource(ringBg)
        ivStatusIcon.setImageResource(icon)
        ivStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        tvStatus.setBackgroundResource(pillBg)
        tvStatus.setTextColor(pillText)
        tvPlan.setTextColor(headlineText)
        tvDaysLeft.setTextColor(headlineText)
        tvExpiry.setTextColor(expiryText)
    }

    // ================= TRIAL =================

    private fun onStartTrialClicked() {
        com.example.easy_billing.util.UserEventLogger.logAction("Subscription", "start_trial_clicked")
        btnStartTrial.isEnabled = false

        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                btnStartTrial.isEnabled = true
                Toast.makeText(this@SubscriptionActivity, R.string.not_logged_in, Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                RetrofitClient.api.startTrial(token)
                Toast.makeText(this@SubscriptionActivity, R.string.free_trial_started, Toast.LENGTH_LONG).show()
                loadSubscription()
                finish()
            } catch (e: retrofit2.HttpException) {
                btnStartTrial.isEnabled = true
                com.example.easy_billing.util.UserEventLogger.logError(
                    "Subscription", "start_trial_failed: HttpException_${e.code()}"
                )
                Toast.makeText(
                    this@SubscriptionActivity,
                    parseErrorDetail(e) ?: getString(R.string.couldnt_start_trial),
                    Toast.LENGTH_LONG
                ).show()
                // A 400 here means the trial was already used (server is
                // the source of truth) — refresh so the card correctly
                // disappears instead of staying visible and re-offering
                // an already-used trial.
                loadSubscription()
            } catch (e: Exception) {
                btnStartTrial.isEnabled = true
                e.printStackTrace()
                com.example.easy_billing.util.UserEventLogger.logError(
                    "Subscription", "start_trial_failed: ${e.javaClass.simpleName}"
                )
                Toast.makeText(this@SubscriptionActivity, R.string.couldnt_start_trial, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================= PLANS =================

    // Only these cycles are ever intentionally offered (1/3/6/12 months,
    // including the 15/30 bonus days baked into the 6- and 12-month
    // durations). Any other duration_days value on a plan row — e.g. a
    // stale legacy row still sitting in the DB with its old duration —
    // is filtered out client-side rather than rendered as its own chip,
    // so a DB-side cleanup isn't a prerequisite for the picker to look
    // right.
    private val knownCycleDays = setOf(30, 90, 195, 395)

    private fun loadPlans() {
        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null) ?: return@launch
            try {
                // Fetch config
                val config = RetrofitClient.api.getSubscriptionConfig()
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("sub_gst_enabled", config.gst_enabled)
                    .putFloat("sub_gst_percent", config.gst_percent)
                    .apply()

                plans = RetrofitClient.api.getPlans(token).filter { it.duration_days in knownCycleDays }
                // Default to the longest cycle available (12 months, i.e.
                // 395 days with its bonus month baked in) rather than the
                // shortest — it's the best-value option and the one we
                // want to nudge users toward. Falls back to whatever the
                // longest available cycle actually is if 395 isn't present
                // for some reason, rather than assuming it always exists.
                selectedCycleDays = plans.map { it.duration_days }.maxOrNull() ?: 30
                renderBillingCycles()
                renderPlans()
                // Premium is the default highlighted tier on first load —
                // renderPlans() just rebuilt the cards for selectedCycleDays,
                // so pick the premium one among them (if present) as the
                // initial selection instead of leaving nothing selected.
                selectedPlan = plans.firstOrNull { it.duration_days == selectedCycleDays && it.tier == "premium" }
                applyPlanSelectionStyles()
                updateContinueButton()
            } catch (e: Exception) {
                e.printStackTrace()
                com.example.easy_billing.util.UserEventLogger.logError(
                    "Subscription", "load_plans_failed: ${e.javaClass.simpleName}"
                )
                Toast.makeText(this@SubscriptionActivity, R.string.couldnt_load_plans, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** "1 Month" / "3 Months" / "6 Months" / "12 Months" for a duration_days bucket. */
    private fun cycleLabel(days: Int): String = when (days) {
        30 -> getString(R.string.sub_cycle_1m)
        90 -> getString(R.string.sub_cycle_3m)
        195 -> getString(R.string.sub_cycle_6m)
        395 -> getString(R.string.sub_cycle_12m)
        else -> getString(R.string.sub_cycle_days, days)
    }

    /**
     * Segmented chip row — one per distinct duration_days value the
     * backend actually returned, sorted shortest to longest. Selecting a
     * chip filters the plan cards below down to just that cycle (Base +
     * Premium), instead of showing all eight plans in one long list.
     */
    private fun renderBillingCycles() {
        llBillingCycle.removeAllViews()
        cycleChipViews.clear()

        val cycles = plans.map { it.duration_days }.distinct().sorted()

        cycles.forEachIndexed { index, days ->
            val chip = TextView(this).apply {
                text = cycleLabel(days)
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index != 0) marginStart = dp(6)
                }
                setOnClickListener { onBillingCycleSelected(days) }
            }
            llBillingCycle.addView(chip)
            cycleChipViews[days] = chip
        }

        updateCycleChipStyles()
    }

    private fun updateCycleChipStyles() {
        for ((days, chip) in cycleChipViews) {
            val selected = days == selectedCycleDays
            chip.setBackgroundResource(if (selected) R.drawable.bg_cycle_chip_selected else R.drawable.bg_cycle_chip_unselected)
            chip.setTextColor(if (selected) 0xFF0F6E56.toInt() else 0xFF374151.toInt())
        }
    }

    private fun onBillingCycleSelected(days: Int) {
        if (days == selectedCycleDays) return
        selectedCycleDays = days
        updateCycleChipStyles()

        // A different cycle means a different price for every plan —
        // any in-flight selection belonged to the old cycle's card and
        // can't carry over.
        selectedPlan = null
        renderPlans()
        updateContinueButton()
    }

    /** Core commitment length a cycle represents, ignoring bonus days
     * (195 days = "6 months" + 15 bonus, 395 = "12 months" + 30 bonus) —
     * used so the savings percentage reflects only the price discount,
     * not double-counting the free bonus days as if they too would have
     * cost money at the monthly rate. */
    private fun coreMonths(days: Int): Int = when (days) {
        30 -> 1
        90 -> 3
        195 -> 6
        395 -> 12
        else -> (days / 30).coerceAtLeast(1)
    }

    /**
     * Monthly price for [plan]'s tier, used as the baseline "Save X%" is
     * computed against — i.e. what this plan's core month count would
     * have cost at the plain monthly rate, not the discounted
     * longer-cycle price.
     */
    private fun monthlyBaselinePaise(plan: PlanResponse): Int? {
        val monthly = plans.firstOrNull { it.tier == plan.tier && it.duration_days == 30 } ?: return null
        return monthly.price_paise * coreMonths(plan.duration_days)
    }

    /** "/mo" / "/3mo" / "/6mo" / "/12mo" — short price suffix for the
     * side-by-side tier cards, where there's no room for the full
     * "699 rupees per 1 Month" phrasing. */
    private fun priceSuffix(days: Int): String = when (days) {
        30 -> getString(R.string.sub_suffix_mo)
        90 -> getString(R.string.sub_suffix_3mo)
        195 -> getString(R.string.sub_suffix_6mo)
        395 -> getString(R.string.sub_suffix_12mo)
        else -> getString(R.string.sub_suffix_n, coreMonths(days))
    }

    private fun renderPlans() {
        llPlans.removeAllViews()
        planCardViews.clear()
        planRadioViews.clear()
        planRadioDotViews.clear()

        val visiblePlans = plans.filter { it.duration_days == selectedCycleDays }

        // Full-width stacked cards (Base above Premium) instead of two
        // side-by-side tiles — gives each tier room for a real feature
        // checklist and a radio selector, the way a subscription picker
        // like Netflix's plan screen reads: one clear column, not a
        // cramped comparison grid.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(10), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        visiblePlans.forEachIndexed { index, plan ->
            val isPremium = plan.tier == "premium"

            // Wrapper so the "Most popular" badge can float above the
            // card's own top edge without being clipped by the column.
            val wrapper = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index != 0) topMargin = dp(14) }
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setBackgroundResource(R.drawable.bg_plan_tier_card_unselected)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (isPremium) dp(9) else 0 }
                isClickable = true
                isFocusable = true
            }

            // ---- Header row: radio + icon + title/desc + price ----
            val headRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val radioDot = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(10), dp(10)).apply { gravity = Gravity.CENTER }
                setBackgroundResource(R.drawable.bg_radio_dot)
                visibility = View.GONE
            }
            val radio = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                setBackgroundResource(R.drawable.bg_radio_ring_off)
                addView(radioDot)
            }
            headRow.addView(radio)
            planRadioViews[plan.plan_code] = radio
            planRadioDotViews[plan.plan_code] = radioDot

            val iconBadge = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(10) }
                setBackgroundResource(if (isPremium) R.drawable.bg_terms_icon_amber else R.drawable.bg_terms_icon_teal)
            }
            iconBadge.addView(ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(14), dp(14)).apply { gravity = Gravity.CENTER }
                setImageResource(if (isPremium) R.drawable.ic_lucide_sparkles else R.drawable.ic_lucide_badge_check)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (isPremium) 0xFF854F0B.toInt() else 0xFF085041.toInt()
                )
            })
            headRow.addView(iconBadge)

            val titleCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                    marginEnd = dp(8)
                }
            }
            titleCol.addView(TextView(this).apply {
                text = if (isPremium) getString(R.string.premium_plan_name) else getString(R.string.sub_base)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF1A1A18.toInt())
            })
            headRow.addView(titleCol)

            val gstEnabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("sub_gst_enabled", false)
            val blurbText = if (gstEnabled) getString(R.string.sub_blurb_gst) else getString(R.string.sub_blurb_no_gst)

            val priceCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            val priceLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            priceLine.addView(TextView(this).apply {
                text = "₹${plan.price_paise / 100}"
                textSize = 19f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (isPremium) 0xFF1A1A18.toInt() else 0xFF0F6E56.toInt())
            })
            priceLine.addView(TextView(this).apply {
                text = priceSuffix(plan.duration_days)
                textSize = 10f
                setTextColor(0xFF9A968C.toInt())
                setPadding(dp(2), 0, 0, dp(2))
            })
            priceCol.addView(priceLine)
            priceCol.addView(TextView(this).apply {
                text = blurbText
                textSize = 9.5f
                setTextColor(0xFFB0A48C.toInt())
                setPadding(0, dp(2), 0, 0)
            })
            headRow.addView(priceCol)

            card.addView(headRow)

            // ---- Divider ----
            card.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { topMargin = dp(13); bottomMargin = dp(13) }
                setBackgroundColor(0xFFF0EDE5.toInt())
            })

            // ---- Feature checklist ----
            val featureStrings = if (isPremium) {
                listOf(
                    getString(R.string.sub_feature_everything_in_base),
                    getString(R.string.dashboard_feature_gst_reports),
                    getString(R.string.subscription_trial_feature_profit_analytics),
                    getString(R.string.subscription_trial_feature_ai_insights),
                    getString(R.string.sub_feature_priority_support)
                )
            } else {
                listOf(
                    getString(R.string.sub_feature_unlimited_billing),
                    getString(R.string.sub_feature_inventory_tracking)
                )
            }
            val featIconBg = if (isPremium) 0xFFFAEEDA.toInt() else 0xFFE1F5EE.toInt()
            val featIconTint = if (isPremium) 0xFF8A6526.toInt() else 0xFF0F6E56.toInt()
            featureStrings.forEachIndexed { i, feature ->
                val featRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { if (i != 0) topMargin = dp(8) }
                }
                val checkCircle = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(17), dp(17))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(featIconBg)
                    }
                }
                checkCircle.addView(ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(10), dp(10)).apply { gravity = Gravity.CENTER }
                    setImageResource(R.drawable.ic_check_filled)
                    imageTintList = android.content.res.ColorStateList.valueOf(featIconTint)
                })
                featRow.addView(checkCircle)
                featRow.addView(TextView(this).apply {
                    text = feature
                    textSize = 12f
                    setTextColor(0xFF374151.toInt())
                    setPadding(dp(8), 0, 0, 0)
                })
                card.addView(featRow)
            }

            // ---- Offer badge: "Save 15% · +7 days free" ----
            var savePart: String? = null
            if (selectedCycleDays != 30) {
                val baseline = monthlyBaselinePaise(plan)
                if (baseline != null && baseline > plan.price_paise) {
                    val savedPct = Math.round((baseline - plan.price_paise) * 100.0 / baseline).toInt()
                    savePart = getString(R.string.sub_save, savedPct)
                }
            }
            val bonusDays = when (selectedCycleDays) {
                195 -> 15
                395 -> 30
                else -> 0
            }
            val bonusPart: String? = when {
                bonusDays == 30 -> getString(R.string.sub_bonus_month)
                bonusDays > 0 -> getString(R.string.sub_bonus_days, bonusDays)
                else -> null
            }
            val offerText = listOfNotNull(savePart, bonusPart).joinToString(" · ")
            if (offerText.isNotEmpty()) {
                card.addView(TextView(this).apply {
                    text = offerText
                    textSize = 11f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(0xFF0F6E56.toInt())
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    setBackgroundResource(R.drawable.bg_savings_badge)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(13) }
                })
            }

            // Reminder that the price above is before service charge + GST
            // — the real, authoritative breakdown (and final total) only
            // appears on the Confirm and pay screen, computed there from
            // the backend's response, never duplicated here. (blurbText is
            // now shown directly under the price in the header row.)

            card.setOnClickListener { onPlanSelected(plan) }
            wrapper.addView(card)

            if (isPremium) {
                wrapper.addView(TextView(this).apply {
                    text = getString(R.string.sub_most_popular_badge)
                    textSize = 9f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    letterSpacing = 0.06f
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundResource(R.drawable.bg_most_popular_badge)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 0; rightMargin = dp(16) }
                })
            }

            column.addView(wrapper)
            planCardViews[plan.plan_code] = card
        }

        llPlans.addView(column)

        // Preserve selection across a re-render (e.g. coming back from
        // ConfirmPaymentActivity) if the previously-selected plan is
        // still among the visible ones for this cycle.
        val stillVisible = selectedPlan?.let { sp -> visiblePlans.firstOrNull { it.plan_code == sp.plan_code } }
        selectedPlan = stillVisible
        applyPlanSelectionStyles()
    }

    private fun onPlanSelected(plan: PlanResponse) {
        selectedPlan = plan
        applyPlanSelectionStyles()
        updateContinueButton()
    }

    private fun applyPlanSelectionStyles() {
        val plan = selectedPlan
        for ((code, view) in planCardViews) {
            val selected = code == plan?.plan_code
            view.setBackgroundResource(if (selected) R.drawable.bg_plan_tier_card_selected else R.drawable.bg_plan_tier_card_unselected)
            planRadioViews[code]?.setBackgroundResource(if (selected) R.drawable.bg_radio_ring_on else R.drawable.bg_radio_ring_off)
            planRadioDotViews[code]?.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    private fun updateContinueButton() {
        val enabled = selectedPlan != null
        findViewById<View>(R.id.tvContinueHint)?.visibility = if (enabled) View.GONE else View.VISIBLE
        btnContinue.isEnabled = enabled
        // MaterialButton is colored via backgroundTint (set in XML as
        // app:backgroundTint), not a background drawable — swapping the
        // ColorStateList here is the equivalent of the old
        // setBackgroundResource() toggle between enabled/disabled art.
        btnContinue.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (enabled) 0xFF0F6E56.toInt() else 0xFFD8D0BC.toInt()
        )
    }

    // ================= CONTINUE =================

    private fun onContinueClicked() {
        val plan = selectedPlan ?: return
        com.example.easy_billing.util.UserEventLogger.logAction(
            "Subscription", "continue_clicked: tier=${plan.tier}, duration_days=${plan.duration_days}"
        )

        // Mirrors the backend's downgrade block (create-order rejects a
        // Base purchase while an active_premium subscription is running,
        // to avoid discarding paid Premium time) — same rule, applied
        // here so the explanation shows immediately on tap instead of
        // after filling out the whole payment popup. The backend is
        // still the actual enforcement; this is only a same-message-
        // earlier shortcut, not a second source of truth.
        if (currentTier == "premium" && currentStatus == "active" && plan.tier == "base") {
            val untilSuffix = currentExpiryLabel?.let { " ($it)" } ?: ""
            com.google.android.material.snackbar.Snackbar.make(
                btnContinue,
                getString(R.string.sub_downgrade_blocked, untilSuffix),
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).show()
            return
        }

        val intent = Intent(this, ConfirmPaymentActivity::class.java).apply {
            putExtra(ConfirmPaymentActivity.EXTRA_PLAN_CODE, plan.plan_code)
            putExtra(ConfirmPaymentActivity.EXTRA_PLAN_TIER, plan.tier)
            putExtra(ConfirmPaymentActivity.EXTRA_PLAN_DURATION_DAYS, plan.duration_days)
            putExtra(ConfirmPaymentActivity.EXTRA_PLAN_PRICE_PAISE, plan.price_paise)
            monthlyBaselinePaise(plan)?.let { putExtra(ConfirmPaymentActivity.EXTRA_BASELINE_PAISE, it) }

            // On-device-only estimate of what the remaining days on the
            // current Base plan are "worth" toward this Premium upgrade —
            // the backend has no tier/proration concept yet (confirmed:
            // no upgrade-credit logic anywhere in pos-backend), so this is
            // NOT sent to create-order and never changes what Razorpay
            // actually charges. It only drives an informational row in
            // ConfirmPaymentActivity — see EXTRA_UPGRADE_CREDIT_PAISE's
            // doc comment there. Daily rate is approximated from the
            // monthly Base plan price (duration_days == 30) since the
            // app doesn't know which duration the active Base plan was
            // actually purchased at, only its remaining_days.
            if (currentTier == "base" && currentStatus == "active" && plan.tier == "premium" && currentRemainingDays > 0) {
                val baseMonthlyPaise = plans.firstOrNull { it.tier == "base" && it.duration_days == 30 }?.price_paise
                if (baseMonthlyPaise != null) {
                    val estimatedCredit = Math.round(baseMonthlyPaise * (currentRemainingDays / 30.0)).toInt()
                        .coerceAtMost(plan.price_paise)
                    if (estimatedCredit > 0) {
                        putExtra(ConfirmPaymentActivity.EXTRA_UPGRADE_CREDIT_PAISE, estimatedCredit)
                        putExtra(ConfirmPaymentActivity.EXTRA_UPGRADE_REMAINING_DAYS, currentRemainingDays)
                    }
                }
            }
        }
        confirmPaymentLauncher.launch(intent)
    }

    private fun parseErrorDetail(e: retrofit2.HttpException): String? {
        return try {
            val body = e.response()?.errorBody()?.string() ?: return null
            org.json.JSONObject(body).optString("detail", null)
        } catch (ex: Exception) {
            null
        }
    }

    // ================= DATE =================

    private fun formatDate(dateStr: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val formatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val date = parser.parse(dateStr)
            formatter.format(date!!)
        } catch (e: Exception) {
            dateStr
        }
    }
}
