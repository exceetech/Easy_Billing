package com.example.easy_billing

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.easy_billing.util.QuickUnlockManager

/**
 * Account picker — shown instead of jumping straight to a PIN screen when
 * MORE THAN ONE account has Quick Unlock set up on this device (see
 * QuickUnlockManager.buildLoginIntent — a single-account device skips this
 * screen entirely and goes straight to that account's PIN/fingerprint
 * screen, since there's nothing to choose between).
 *
 * Picking an account here does NOT itself check a PIN — it hands off to
 * QuickUnlockActivity(MODE_UNLOCK) for that specific account, which asks
 * for that account's PIN or fingerprint and then replays its login.
 *
 * Also offers the two escape hatches every login surface needs: signing in
 * with a different (or not-yet-remembered) account's full email+password,
 * and creating a brand-new account.
 *
 * Champagne redesign: same teal icon-tile + pulsing-ring treatment as
 * QuickUnlockActivity, gradient-avatar account rows (cycling through 3
 * accent hues, purely cosmetic — not tied to any per-account color choice)
 * with a "Quick Unlock" badge, and pill-style footer actions.
 */
class AccountSelectionActivity : BaseActivity() {

    private var pulseAnimator: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_selection)

        val accounts = QuickUnlockManager.getAccounts(this)

        // Nothing (or only one) left to choose between — e.g. the other
        // account's PIN got cleared between this Intent being built and
        // this screen actually opening. Re-route immediately rather than
        // showing an empty/pointless picker.
        if (accounts.size <= 1) {
            startActivity(QuickUnlockManager.buildLoginIntent(this))
            finish()
            return
        }

        startPulseRings()

        val lastActive = QuickUnlockManager.getLastUsername(this)
        val container = findViewById<LinearLayout>(R.id.accountsContainer)
        accounts.forEachIndexed { index, username ->
            container.addView(buildAccountRow(username, index, isLastActive = username == lastActive))
        }

        findViewById<TextView>(R.id.tvLoginWithPassword).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<TextView>(R.id.tvCreateAccount).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }
    }

    /** Two faint rings behind the icon tile, pulsing outward on a
     *  staggered loop — same treatment as QuickUnlockActivity's icon. */
    private fun startPulseRings() {
        val ring1 = findViewById<View>(R.id.ringPulse1)
        val ring2 = findViewById<View>(R.id.ringPulse2)
        val set1 = buildRingPulse(ring1, startDelay = 0L)
        val set2 = buildRingPulse(ring2, startDelay = 900L)
        pulseAnimator = AnimatorSet().apply {
            playTogether(set1, set2)
            start()
        }
    }

    private fun buildRingPulse(ring: View, startDelay: Long): AnimatorSet {
        ring.scaleX = 0.78f
        ring.scaleY = 0.78f
        ring.alpha = 0.9f
        val scaleX = ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.78f, 1.35f).apply { duration = 2600 }
        val scaleY = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.78f, 1.35f).apply { duration = 2600 }
        val alpha = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.9f, 0f).apply { duration = 2600 }
        val set = AnimatorSet()
        set.playTogether(scaleX, scaleY, alpha)
        set.interpolator = AccelerateDecelerateInterpolator()
        set.startDelay = startDelay
        set.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!isFinishing && !isDestroyed) {
                    ring.scaleX = 0.78f
                    ring.scaleY = 0.78f
                    ring.alpha = 0.9f
                    set.startDelay = 0
                    set.start()
                }
            }
        })
        return set
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDestroy()
    }

    private val avatarGradients = listOf(
        R.drawable.bg_avatar_gradient_1,
        R.drawable.bg_avatar_gradient_2,
        R.drawable.bg_avatar_gradient_3
    )

    private fun buildAccountRow(username: String, index: Int, isLastActive: Boolean): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_send_option_row)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setOnClickListener {
                val intent = Intent(this@AccountSelectionActivity, QuickUnlockActivity::class.java)
                intent.putExtra(QuickUnlockActivity.EXTRA_MODE, QuickUnlockActivity.MODE_UNLOCK)
                intent.putExtra(QuickUnlockActivity.EXTRA_USERNAME, username)
                startActivity(intent)
                finish()
            }
        }

        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            gravity = Gravity.CENTER
            setBackgroundResource(avatarGradients[index % avatarGradients.size])
            text = username.trim().take(1).uppercase()
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(12) }
        }

        val label = TextView(this).apply {
            text = username
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#1A1A18"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }

        val badge = TextView(this).apply {
            text = "  Quick Unlock"
            textSize = 8.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#0F6E56"))
            setBackgroundResource(R.drawable.bg_quick_unlock_badge)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            val icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_lucide_check)
            icon?.setTint(android.graphics.Color.parseColor("#0F6E56"))
            icon?.setBounds(0, 0, dp(8), dp(8))
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dp(3)
        }

        metaRow.addView(badge)

        if (isLastActive) {
            val activeText = TextView(this).apply {
                text = "Active now"
                textSize = 9.5f
                setTextColor(android.graphics.Color.parseColor("#9A8F79"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
            }
            metaRow.addView(activeText)
        }

        textCol.addView(label)
        textCol.addView(metaRow)

        val chevron = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9))
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#C9C3B4"))
        }

        row.addView(avatar)
        row.addView(textCol)
        row.addView(chevron)
        return row
    }
}
