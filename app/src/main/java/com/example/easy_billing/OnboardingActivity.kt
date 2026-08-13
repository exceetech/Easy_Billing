package com.example.easy_billing

import com.example.easy_billing.R

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.ProfileResponse
import com.example.easy_billing.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

/**
 * First-time onboarding wizard — orchestrator, not a form host.
 *
 * Rather than re-implementing subscription/shop-info/billing/terms as
 * embedded fragments, this launches the existing, already-working,
 * full-screen Activities for each step (SubscriptionActivity,
 * StoreSettingsActivity, BillingSettingsActivity, TermsActivity) in a
 * fixed order, and re-reads GET /auth/me on every onResume to see which
 * step is next — this is what makes the flow resumable if the user is
 * interrupted mid-wizard (plan §2.6): each step's own save action
 * independently marks its own onboarding_*_done flag server-side, so
 * this screen never needs to track progress itself beyond what the
 * server reports.
 *
 * Order is fixed and non-skippable: Subscription → Shop info → Billing
 * → Terms (plan §2.1). This particular order matters technically, not
 * just as UX: every other authenticated endpoint in the app (including
 * shop-settings and billing-settings saves) requires an active/trial
 * subscription to even be reachable, so Subscription genuinely has to
 * be first or steps 2-3 would 403 before they could ever complete.
 */
class OnboardingActivity : BaseActivity() {

    private data class Step(
        val key: String,
        val title: String,
        val subtitle: String,
        val isDone: (ProfileResponse) -> Boolean,
        val activityClass: Class<*>
    )

    private val steps = listOf(
        Step("subscription", "Choose a plan", "Start a free trial or subscribe",
            { it.onboarding_subscription_done }, SubscriptionActivity::class.java),
        Step("shop_info", "Shop information", "Tell us about your shop",
            { it.onboarding_shop_info_done }, StoreSettingsActivity::class.java),
        Step("billing", "Billing settings", "Set your default tax and invoice layout",
            { it.onboarding_billing_done }, BillingSettingsActivity::class.java),
        Step("terms", "Terms and conditions", "Review and accept to continue",
            { it.onboarding_terms_done }, TermsActivity::class.java),
    )

    private lateinit var llSteps: LinearLayout
    private lateinit var progressOnboarding: ProgressBar
    private lateinit var llOnboardStepper: LinearLayout
    private lateinit var tvOnboardStepCount: TextView

    private var completing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        com.example.easy_billing.util.UserEventLogger.logAction("Onboarding", "opened")

        // No back navigation — onboarding is non-skippable, so the toolbar
        // is shown for consistent spacing/branding only, no back arrow.
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        llSteps = findViewById(R.id.llSteps)
        progressOnboarding = findViewById(R.id.progressOnboarding)
        llOnboardStepper = findViewById(R.id.llOnboardStepper)
        tvOnboardStepCount = findViewById(R.id.tvOnboardStepCount)
    }

    /**
     * Launches a step's Activity. Only ever called for the current
     * (first-incomplete) step — order is still strictly enforced, this
     * just moves the tap target from a separate "Continue" button onto
     * the step's own card, so returning to the hub after finishing a
     * step doesn't require an extra tap to pick the same next step again.
     */
    private fun launchStep(cls: Class<*>) {
        val intent = Intent(this, cls)
        // Skip the password re-verification step on these two screens
        // when reached via onboarding — see the matching isOnboardingFlow
        // flag in each Activity (plan §2.3).
        if (cls == StoreSettingsActivity::class.java) {
            intent.putExtra(StoreSettingsActivity.EXTRA_ONBOARDING, true)
        } else if (cls == BillingSettingsActivity::class.java) {
            intent.putExtra(BillingSettingsActivity.EXTRA_ONBOARDING, true)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshProgress()
    }

    private fun refreshProgress() {
        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                finish()
                return@launch
            }

            // Only show the spinner on the very first load — later
            // onResume() refreshes shouldn't flicker over already-visible
            // step cards.
            val isFirstLoad = llSteps.childCount == 0
            if (isFirstLoad) {
                progressOnboarding.visibility = View.VISIBLE
            }

            try {
                val profile = RetrofitClient.api.getProfile(token)
                progressOnboarding.visibility = View.GONE

                // Fast path for a returning, already-onboarded shop
                // (e.g. MainActivity routes every non-first-login here
                // unconditionally, since LoginResponse doesn't carry
                // onboarding status itself — see MainActivity's login
                // success handler). Skip straight to Dashboard without
                // re-calling complete-onboarding, which would otherwise
                // fire on every single login for no reason.
                //
                // Also respects the kill switch (plan §6.6) — if the
                // backend has turned onboarding enforcement off, this
                // screen must not keep forcing shops through the wizard
                // just because they landed here from MainActivity's
                // unconditional route.
                if (profile.onboarding_completed_at != null || !profile.onboarding_enforcement_enabled) {
                    startActivity(
                        Intent(this@OnboardingActivity, DashboardActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    )
                    finish()
                    return@launch
                }

                renderSteps(profile)

                val firstIncompleteIndex = steps.indexOfFirst { !it.isDone(profile) }
                updateProgress(firstIncompleteIndex)

                if (firstIncompleteIndex == -1) {
                    finishOnboarding()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                progressOnboarding.visibility = View.GONE
                com.google.android.material.snackbar.Snackbar.make(
                    llSteps,
                    getString(R.string.onboardingactivity_couldnt_load_setup_progress),
                    com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
                ).setAction(R.string.retry) { refreshProgress() }.show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Champagne/teal step card matching the app-wide onboarding redesign. */
    private fun renderSteps(profile: ProfileResponse) {
        llSteps.removeAllViews()
        val firstIncompleteIndex = steps.indexOfFirst { !it.isDone(profile) }

        steps.forEachIndexed { index, step ->
            val done = step.isDone(profile)
            val isCurrent = index == firstIncompleteIndex
            val isPending = !done && !isCurrent

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(
                    if (isCurrent) R.drawable.bg_onboard_step_current else R.drawable.bg_onboard_step_card
                )
                alpha = if (isPending) 0.65f else 1f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else dp(10) }

                // Order is still enforced — only the current step's card
                // is tappable. Done/upcoming rows stay inert; tapping the
                // card itself replaces the old separate "Continue" button.
                if (isCurrent) {
                    isClickable = true
                    isFocusable = true
                    val outValue = TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    foreground = ContextCompat.getDrawable(this@OnboardingActivity, outValue.resourceId)
                    setOnClickListener {
                        com.example.easy_billing.util.UserEventLogger.logAction(
                            "Onboarding", "step_clicked: step=${index + 1}"
                        )
                        launchStep(step.activityClass)
                    }
                }
            }

            val avatar = TextView(this).apply {
                text = if (done) "✓" else "${index + 1}"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    if (done || isCurrent) 0xFF085041.toInt() else 0xFF9C9482.toInt()
                )
                setBackgroundResource(
                    if (done || isCurrent) R.drawable.bg_onboard_avatar_current
                    else R.drawable.bg_onboard_avatar_pending
                )
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            }

            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                }
            }

            val title = TextView(this).apply {
                text = step.title
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF1A1A18.toInt())
            }
            val subtitle = TextView(this).apply {
                text = step.subtitle
                textSize = 11.5f
                setTextColor(0xFF8A8474.toInt())
                setPadding(0, dp(2), 0, 0)
            }

            textColumn.addView(title)
            textColumn.addView(subtitle)

            row.addView(avatar)
            row.addView(textColumn)

            if (isCurrent) {
                val chevron = TextView(this).apply {
                    text = "›"
                    textSize = 20f
                    setTextColor(0xFFB8895A.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                row.addView(chevron)
            }

            llSteps.addView(row)
        }
    }

    /**
     * Node stepper: a circle per step connected by lines, replacing the
     * earlier continuous fill bar. Done steps are solid teal with a
     * checkmark, the current step is an outlined teal ring with its
     * number, and upcoming steps are dimmed tan circles — connector
     * segments turn teal only once the node before them is done.
     */
    private fun updateProgress(firstIncompleteIndex: Int) {
        val total = steps.size
        val completedCount = if (firstIncompleteIndex == -1) total else firstIncompleteIndex
        val currentStepNumber = (completedCount + 1).coerceAtMost(total)

        tvOnboardStepCount.text = if (firstIncompleteIndex == -1) {
            "All steps complete"
        } else {
            "Step $currentStepNumber of $total · ${steps[firstIncompleteIndex].title}"
        }

        llOnboardStepper.removeAllViews()
        val nodeSize = dp(24)

        for (index in 0 until total) {
            val isDone = firstIncompleteIndex == -1 || index < firstIncompleteIndex
            val isCurrent = index == firstIncompleteIndex

            val node = FrameLayout(this).apply {
                setBackgroundResource(
                    when {
                        isDone -> R.drawable.bg_stepper_node_done
                        isCurrent -> R.drawable.bg_stepper_node_current
                        else -> R.drawable.bg_stepper_node_pending
                    }
                )
                layoutParams = LinearLayout.LayoutParams(nodeSize, nodeSize)
            }

            if (isDone) {
                val check = ImageView(this).apply {
                    setImageDrawable(AppCompatResources.getDrawable(this@OnboardingActivity, R.drawable.ic_lucide_check))
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFFFCF3E5.toInt())
                    layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply { gravity = Gravity.CENTER }
                }
                node.addView(check)
            } else {
                val label = TextView(this).apply {
                    text = "${index + 1}"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(if (isCurrent) 0xFF0F6E56.toInt() else 0xFF9C9482.toInt())
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                node.addView(label)
            }

            llOnboardStepper.addView(node)

            if (index != total - 1) {
                val connector = View(this).apply {
                    setBackgroundColor(if (index < completedCount) 0xFF0F6E56.toInt() else 0xFFE7DEC8.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, dp(2), 1f)
                }
                llOnboardStepper.addView(connector)
            }
        }
    }

    private fun finishOnboarding() {
        if (completing) return
        completing = true

        progressOnboarding.visibility = View.VISIBLE

        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                completing = false
                return@launch
            }

            try {
                // Re-verifies all four steps server-side before stamping
                // completion (plan §2.5) — this call is the actual gate,
                // not the client-side "all steps done" check above, which
                // only decides when to attempt it.
                RetrofitClient.api.completeOnboarding(token)
                startActivity(
                    Intent(this@OnboardingActivity, DashboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
                finish()
            } catch (e: Exception) {
                e.printStackTrace()
                completing = false
                progressOnboarding.visibility = View.GONE
                Toast.makeText(this@OnboardingActivity, R.string.couldnt_finish_setup_try_again, Toast.LENGTH_SHORT).show()
                refreshProgress()
            }
        }
    }
}
