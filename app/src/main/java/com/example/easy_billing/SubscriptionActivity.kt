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
    private var planSelectedPillViews: MutableMap<String, TextView> = mutableMapOf()

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

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        // Preloading here (instead of only in ConfirmPaymentActivity) keeps
        // the Razorpay SDK warm by the time the user reaches checkout.
        Checkout.preload(applicationContext)

        cardStatus = findViewById(R.id.cardStatus)
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

                tvPlan.text = if (hasPlan) "${res.plan ?: res.tier}" else "No active plan"

                tvExpiry.text = when {
                    !hasPlan -> "Choose a plan to get started"
                    res.expiry_ms != null ->
                        "Renews ${com.example.easy_billing.util.AppTime.formatter("dd MMM yyyy").format(java.util.Date(res.expiry_ms))}"
                    res.expiry_date != null -> "Renews ${formatDate(res.expiry_date)}"
                    else -> "Renews -"
                }

                tvDaysLeft.text = if (hasPlan) "${res.remaining_days} days left" else "Pick a plan below"

                // "trial" is a genuinely usable, active status — must not
                // fall into the same visual bucket as "expired" the way a
                // naive `if (status == "active")` check would (see the
                // backend fix in dependencies.get_current_shop for the
                // same class of bug on the enforcement side).
                val statusLabel: String
                val cardState: StatusCardState
                when {
                    !hasPlan -> {
                        statusLabel = "Get started"
                        cardState = StatusCardState.NO_PLAN
                    }
                    res.status == "trial" -> {
                        statusLabel = "Trial"
                        cardState = StatusCardState.TRIAL
                    }
                    res.status == "active" && res.remaining_days <= endingSoonThresholdDays -> {
                        statusLabel = "Ending soon"
                        cardState = StatusCardState.ENDING_SOON
                    }
                    res.status == "active" -> {
                        statusLabel = "Active"
                        cardState = StatusCardState.ACTIVE
                    }
                    else -> {
                        statusLabel = "Expired"
                        cardState = StatusCardState.NO_PLAN
                    }
                }
                tvStatus.text = statusLabel
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
                com.google.android.material.snackbar.Snackbar.make(
                    tvPlan,
                    getString(R.string.subscriptionactivity_failed_to_load),
                    com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
                ).setAction(R.string.retry) { loadSubscription() }.show()
            }
        }
    }

    /** Color states the shared status-card template can render as — one
     * bordered-tint layout, four palettes swapped at runtime. */
    private enum class StatusCardState { ACTIVE, ENDING_SOON, NO_PLAN, TRIAL }

    private fun applyStatusCardState(state: StatusCardState) {
        val cardBg: Int
        val iconBg: Int
        val pillBg: Int
        val icon: Int
        // Now that the card itself is a solid, dark ramp-600 fill, title
        // text goes light (ramp-50) and the subtitle a shade deeper
        // (ramp-100) for hierarchy — the reverse of the old pale-tint
        // card, which used dark text on a light fill.
        val titleText: Int
        val subtitleText: Int

        when (state) {
            StatusCardState.ACTIVE -> {
                cardBg = R.drawable.bg_status_card_teal
                iconBg = R.drawable.bg_status_icon_teal
                pillBg = R.drawable.bg_status_pill_teal
                icon = R.drawable.ic_lucide_badge_check
                titleText = 0xFFE1F5EE.toInt()
                subtitleText = 0xFF9FE1CB.toInt()
            }
            StatusCardState.ENDING_SOON -> {
                cardBg = R.drawable.bg_status_card_amber
                iconBg = R.drawable.bg_status_icon_amber
                pillBg = R.drawable.bg_status_pill_amber
                icon = R.drawable.ic_lc_clock
                titleText = 0xFFFAEEDA.toInt()
                subtitleText = 0xFFFAC775.toInt()
            }
            StatusCardState.NO_PLAN -> {
                cardBg = R.drawable.bg_status_card_coral
                iconBg = R.drawable.bg_status_icon_coral
                pillBg = R.drawable.bg_status_pill_coral
                icon = R.drawable.ic_lucide_alert
                titleText = 0xFFFAECE7.toInt()
                subtitleText = 0xFFF5C4B3.toInt()
            }
            StatusCardState.TRIAL -> {
                cardBg = R.drawable.bg_status_card_purple
                iconBg = R.drawable.bg_status_icon_purple
                pillBg = R.drawable.bg_status_pill_purple
                icon = R.drawable.ic_lucide_sparkles
                titleText = 0xFFEEEDFE.toInt()
                subtitleText = 0xFFCECBF6.toInt()
            }
        }

        cardStatus.setBackgroundResource(cardBg)
        statusIconBadge.setBackgroundResource(iconBg)
        ivStatusIcon.setImageResource(icon)
        ivStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(titleText)
        tvStatus.setBackgroundResource(pillBg)
        tvStatus.setTextColor(titleText)
        tvPlan.setTextColor(titleText)
        tvDaysLeft.setTextColor(titleText)
        tvExpiry.setTextColor(subtitleText)
    }

    // ================= TRIAL =================

    private fun onStartTrialClicked() {
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
                Toast.makeText(this@SubscriptionActivity, R.string.couldnt_load_plans, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** "1 Month" / "3 Months" / "6 Months" / "12 Months" for a duration_days bucket. */
    private fun cycleLabel(days: Int): String = when (days) {
        30 -> "1 Month"
        90 -> "3 Months"
        195 -> "6 Months"
        395 -> "12 Months"
        else -> "$days days"
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
        30 -> "/mo"
        90 -> "/3mo"
        195 -> "/6mo"
        395 -> "/12mo"
        else -> "/${coreMonths(days)}mo"
    }

    private fun renderPlans() {
        llPlans.removeAllViews()
        planCardViews.clear()
        planSelectedPillViews.clear()

        val visiblePlans = plans.filter { it.duration_days == selectedCycleDays }

        // Two vertical tier cards side by side (Base | Premium) instead of
        // a stacked list — same information, denser layout since there
        // are only ever two tiers per cycle. Extra top padding on the row
        // itself (rather than on each card) leaves room for the
        // "Selected" pill to float above the selected card's top-left
        // corner, overlapping its border, without being clipped.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(9), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        visiblePlans.forEachIndexed { index, plan ->
            val isPremium = plan.tier == "premium"

            // Each tier is a FrameLayout wrapper (card + floating pill)
            // rather than the card itself, so the pill can sit above the
            // card's own top edge without needing a negative margin that
            // could get clipped by the parent.
            val wrapper = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index != 0) marginStart = dp(10)
                }
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                setBackgroundResource(R.drawable.bg_plan_tier_card_unselected)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(9) }
                isClickable = true
                isFocusable = true
            }

            val iconBadge = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setBackgroundResource(if (isPremium) R.drawable.bg_terms_icon_amber else R.drawable.bg_terms_icon_teal)
            }
            val icon = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(13), dp(13)).apply { gravity = Gravity.CENTER }
                setImageResource(if (isPremium) R.drawable.ic_lucide_sparkles else R.drawable.ic_lucide_badge_check)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (isPremium) 0xFF854F0B.toInt() else 0xFF085041.toInt()
                )
            }
            iconBadge.addView(icon)
            card.addView(iconBadge)

            val title = TextView(this).apply {
                text = if (isPremium) getString(R.string.premium_plan_name) else "Base"
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF1A1A18.toInt())
                setPadding(0, dp(7), 0, 0)
            }
            card.addView(title)

            val priceRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(1) }
            }
            priceRow.addView(TextView(this).apply {
                text = "₹${plan.price_paise / 100}"
                textSize = 16.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (isPremium) 0xFF1A1A18.toInt() else 0xFF0F6E56.toInt())
            })
            priceRow.addView(TextView(this).apply {
                text = priceSuffix(plan.duration_days)
                textSize = 10f
                setTextColor(0xFF8A8474.toInt())
                setPadding(dp(2), 0, 0, dp(2))
            })
            card.addView(priceRow)

            // Reminder that the price above is before service charge + GST
            // — the real, authoritative breakdown (and final total) only
            // appears on the Confirm and pay screen, computed there from
            // the backend's response, never duplicated here.
            val blurb = TextView(this).apply {
                text = "+ GST + service charges"
                textSize = 10.5f
                setTextColor(0xFF8A8474.toInt())
                setPadding(0, dp(3), 0, 0)
            }
            card.addView(blurb)

            // Badges — savings % (past the monthly cycle) and bonus days
            // (6/12-month cycles), stacked below the blurb.
            if (selectedCycleDays != 30) {
                val baseline = monthlyBaselinePaise(plan)
                if (baseline != null && baseline > plan.price_paise) {
                    val savedPaise = baseline - plan.price_paise
                    val savedPct = Math.round(savedPaise * 100.0 / baseline).toInt()
                    card.addView(TextView(this).apply {
                        text = "Save $savedPct%"
                        textSize = 10f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(0xFF0F6E56.toInt())
                        setPadding(dp(7), dp(3), dp(7), dp(3))
                        setBackgroundResource(R.drawable.bg_savings_badge)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(6) }
                    })
                }
            }

            val bonusDays = when (selectedCycleDays) {
                195 -> 15
                395 -> 30
                else -> 0
            }
            if (bonusDays > 0) {
                card.addView(TextView(this).apply {
                    text = if (bonusDays == 30) "+1 month free" else "+$bonusDays days free"
                    textSize = 10f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(0xFFB8791A.toInt())
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                    setBackgroundResource(R.drawable.bg_bonus_badge)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                })
            }

            card.setOnClickListener { onPlanSelected(plan) }
            wrapper.addView(card)

            // Floating "Selected" pill — sits at the wrapper's actual top
            // (y=0), while the card itself starts dp(9) lower, so the pill
            // visually overlaps the card's top-left border corner.
            val selectedPill = TextView(this).apply {
                text = "Selected"
                textSize = 9f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFFFCF3E5.toInt())
                setPadding(dp(8), dp(3), dp(8), dp(3))
                setBackgroundResource(R.drawable.bg_plan_selected_pill)
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    marginStart = dp(12)
                }
            }
            wrapper.addView(selectedPill)

            row.addView(wrapper)
            planCardViews[plan.plan_code] = card
            planSelectedPillViews[plan.plan_code] = selectedPill
        }

        llPlans.addView(row)

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
            planSelectedPillViews[code]?.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    private fun updateContinueButton() {
        val enabled = selectedPlan != null
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
                "You're on Premium — switching to Base isn't available until your current period ends$untilSuffix.",
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
