package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Onboarding step 4 — Terms and Conditions acceptance.
 *
 * "I agree" stays visually locked (grey, lock icon) until the user has
 * actually scrolled to the bottom of the terms text — a deliberate choice
 * per the onboarding plan §2.4, so acceptance means the user at least
 * scrolled past the whole document rather than tapping through a screen
 * they never read. A live reading-progress bar tracks scroll position so
 * the "locked" state doesn't feel like a dead end.
 *
 * On accept, calls POST /auth/accept-terms and finishes — the caller
 * (OnboardingActivity) re-checks progress on its next onResume and
 * advances automatically; this Activity doesn't need to know what step
 * comes next.
 */
class TermsActivity : BaseActivity() {

    private lateinit var scrollTerms: ScrollView
    private lateinit var btnAcceptTerms: MaterialButton
    private lateinit var llScrollHint: View
    private lateinit var viewTermsProgress: View
    private lateinit var tvTermsProgressPct: TextView
    private lateinit var viewTermsFade: View
    private lateinit var tvTermsUnlockHint: TextView

    private var reachedBottom = false
    // Highest scroll fraction seen so far — the bar tracks "how much
    // you've read" and should never step backward just because the user
    // scrolled back up to re-read something.
    private var maxReadFraction = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terms)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        scrollTerms = findViewById(R.id.scrollTerms)
        btnAcceptTerms = findViewById(R.id.btnAcceptTerms)
        llScrollHint = findViewById(R.id.llScrollHint)
        viewTermsProgress = findViewById(R.id.viewTermsProgress)
        tvTermsProgressPct = findViewById(R.id.tvTermsProgressPct)
        viewTermsFade = findViewById(R.id.viewTermsFade)
        tvTermsUnlockHint = findViewById(R.id.tvTermsUnlockHint)

        renderTermsSections(getString(R.string.terms_and_conditions_body))

        scrollTerms.viewTreeObserver.addOnScrollChangedListener {
            updateReadingProgress()
        }

        // Short terms text / a tall screen might already show the whole
        // document without any scrolling at all — don't trap the user
        // waiting for a scroll event that will never fire.
        scrollTerms.post { updateReadingProgress() }

        btnAcceptTerms.setOnClickListener { onAcceptClicked() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Icon + accent color per section, matched by keyword against the
     * section title so each clause reads as its own category at a glance
     * instead of a flat numbered list — teal for account/data clauses,
     * amber for legal/money-back ones.
     */
    private data class TimelineStyle(val icon: Int, val iconTint: Int, val badgeBg: Int)

    private fun styleFor(title: String): TimelineStyle {
        val t = title.lowercase()
        return when {
            "accept" in t -> TimelineStyle(R.drawable.ic_lc_shield_check, 0xFF085041.toInt(), R.drawable.bg_terms_icon_teal)
            "subscription" in t || "billing" in t -> TimelineStyle(R.drawable.ic_lucide_credit_card, 0xFF085041.toInt(), R.drawable.bg_terms_icon_teal)
            "trial" in t -> TimelineStyle(R.drawable.ic_lc_clock, 0xFF085041.toInt(), R.drawable.bg_terms_icon_teal)
            "data" in t || "privacy" in t -> TimelineStyle(R.drawable.ic_lc_lock, 0xFF085041.toInt(), R.drawable.bg_terms_icon_teal)
            "refund" in t -> TimelineStyle(R.drawable.ic_lucide_receipt, 0xFF854F0B.toInt(), R.drawable.bg_terms_icon_amber)
            "change" in t -> TimelineStyle(R.drawable.ic_lc_rotate_ccw, 0xFF854F0B.toInt(), R.drawable.bg_terms_icon_amber)
            "contact" in t -> TimelineStyle(R.drawable.ic_lc_mail, 0xFF854F0B.toInt(), R.drawable.bg_terms_icon_amber)
            else -> TimelineStyle(R.drawable.ic_lc_shield_check, 0xFF085041.toInt(), R.drawable.bg_terms_icon_teal)
        }
    }

    /**
     * Vertical timeline: each clause gets a category icon connected to the
     * next by a thin line, so the whole document reads as one continuous
     * flow instead of separate numbered cards. Replaces the earlier
     * numbered-badge design entirely.
     */
    private fun renderTermsSections(raw: String) {
        val llTermsSections = findViewById<LinearLayout>(R.id.llTermsSections)
        llTermsSections.removeAllViews()

        val sectionPattern = Regex("""(?m)^(\d+)\.\s+(.+)$""")
        val matches = sectionPattern.findAll(raw).toList()

        // Preamble — anything before the first numbered heading (the
        // "Easy Billing — Terms and Conditions (Draft)" line).
        val preambleEnd = matches.firstOrNull()?.range?.first ?: raw.length
        val preamble = raw.substring(0, preambleEnd).trim()
        if (preamble.isNotEmpty()) {
            val preambleView = TextView(this).apply {
                text = preamble
                textSize = 11.5f
                setTypeface(typeface, android.graphics.Typeface.ITALIC)
                setTextColor(0xFF9C9482.toInt())
                setPadding(0, 0, 0, dp(16))
            }
            llTermsSections.addView(preambleView)
        }

        val iconBadgeSize = dp(32)
        val lineWidth = dp(2)

        matches.forEachIndexed { index, match ->
            val title = match.groupValues[2].trim()
            val bodyStart = match.range.last + 1
            val bodyEnd = matches.getOrNull(index + 1)?.range?.first ?: raw.length
            val body = raw.substring(bodyStart, bodyEnd).trim()
            val isLast = index == matches.lastIndex
            val style = styleFor(title)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Icon rail: badge on top, thin connecting line running down
            // to the next clause (omitted after the last one).
            val rail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(iconBadgeSize, LinearLayout.LayoutParams.MATCH_PARENT)
            }

            val iconBadge = FrameLayout(this).apply {
                setBackgroundResource(style.badgeBg)
                layoutParams = LinearLayout.LayoutParams(iconBadgeSize, iconBadgeSize)
            }
            val icon = ImageView(this).apply {
                setImageDrawable(AppCompatResources.getDrawable(this@TermsActivity, style.icon))
                imageTintList = android.content.res.ColorStateList.valueOf(style.iconTint)
                layoutParams = FrameLayout.LayoutParams(dp(16), dp(16)).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            iconBadge.addView(icon)
            rail.addView(iconBadge)

            if (!isLast) {
                val line = View(this).apply {
                    setBackgroundColor(0xFFE7DEC8.toInt())
                    layoutParams = LinearLayout.LayoutParams(lineWidth, 0, 1f).apply {
                        topMargin = dp(4)
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                    }
                }
                rail.addView(line)
            }

            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                    bottomMargin = if (isLast) 0 else dp(22)
                }
            }

            val titleView = TextView(this).apply {
                text = title
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF1A1A18.toInt())
            }
            val bodyView = TextView(this).apply {
                text = body
                textSize = 12f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(0xFF8A8474.toInt())
                setPadding(0, dp(4), 0, 0)
            }

            textColumn.addView(titleView)
            textColumn.addView(bodyView)

            row.addView(rail)
            row.addView(textColumn)
            llTermsSections.addView(row)
        }
    }

    private fun updateReadingProgress() {
        val child = scrollTerms.getChildAt(0) ?: return
        val scrollable = (child.height - scrollTerms.height).coerceAtLeast(1)
        val currentFraction = (scrollTerms.scrollY.toFloat() / scrollable).coerceIn(0f, 1f)

        // Only ever move forward — scrolling back up to re-read a clause
        // shouldn't make the bar (or a completed 100%) drop back down.
        maxReadFraction = maxOf(maxReadFraction, currentFraction)
        val fraction = maxReadFraction

        val track = viewTermsProgress.parent as View
        track.post {
            viewTermsProgress.layoutParams = viewTermsProgress.layoutParams.apply {
                width = (track.width * fraction).toInt().coerceAtLeast(dp(6))
            }
            viewTermsProgress.requestLayout()
        }
        tvTermsProgressPct.text = "${(fraction * 100).toInt()}%"

        if (reachedBottom) return

        val diff = child.bottom - (scrollTerms.height + scrollTerms.scrollY)
        // A few px of tolerance — exact-zero is unreliable across devices.
        if (diff <= 24) {
            reachedBottom = true
            unlockAcceptButton()
        }
    }

    private fun unlockAcceptButton() {
        btnAcceptTerms.isEnabled = true
        btnAcceptTerms.setTextColor(0xFFFFFFFF.toInt())
        btnAcceptTerms.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF0F6E56.toInt())
        btnAcceptTerms.icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_lucide_check)
        btnAcceptTerms.iconTint = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        llScrollHint.visibility = View.GONE
        viewTermsFade.visibility = View.GONE
        tvTermsUnlockHint.visibility = View.GONE
    }

    private fun onAcceptClicked() {
        if (!reachedBottom) return
        btnAcceptTerms.isEnabled = false

        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                Toast.makeText(this@TermsActivity, R.string.not_logged_in, Toast.LENGTH_SHORT).show()
                btnAcceptTerms.isEnabled = true
                return@launch
            }

            try {
                RetrofitClient.api.acceptTerms(token)
                finish()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@TermsActivity, R.string.couldnt_save_try_again, Toast.LENGTH_SHORT).show()
                btnAcceptTerms.isEnabled = true
            }
        }
    }
}
