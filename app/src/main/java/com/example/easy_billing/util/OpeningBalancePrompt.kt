package com.example.easy_billing.util

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run nudge on the dashboard: a small bottom-right card asking the owner to set the opening
 * balance. Shown only while no opening balance exists and it has not been skipped.
 *
 *  - "Set it now" opens the opening-balance popup. Saving it ends the prompt (an opening balance now
 *    exists). Cancelling inside the popup does not skip: the prompt returns next time.
 *  - "Skip" is remembered per shop account on this phone and the prompt never shows again.
 */
object OpeningBalancePrompt {

    private const val PREFS = "opening_prompt"
    private const val TAG_VIEW = "opening_balance_prompt"

    private fun key(context: Context): String {
        val user = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("USERNAME", null) ?: "default"
        return "skipped_$user"
    }

    private fun isSkipped(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key(context), false)

    private fun setSkipped(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key(context), true).apply()

    fun maybeShow(activity: AppCompatActivity) {
        val hasToken = activity.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) != null
        if (!hasToken || isSkipped(activity)) return
        val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (host.findViewWithTag<View>(TAG_VIEW) != null) return

        activity.lifecycleScope.launch {
            // Give a restored account a moment to pull its opening balance before we ask for one.
            delay(1500)
            val needs = withContext(Dispatchers.IO) {
                runCatching { AppDatabase.getDatabase(activity).moneyEntryDao().countActiveOpening() == 0 }.getOrDefault(false)
            }
            if (!needs || activity.isFinishing || activity.isDestroyed || isSkipped(activity)) return@launch
            if (host.findViewWithTag<View>(TAG_VIEW) != null) return@launch

            // Full-screen shield: soft dim behind the card that swallows every touch, so nothing else on
            // the dashboard can be tapped until the owner chooses "Set it now" or "Skip".
            val shield = FrameLayout(activity).apply {
                tag = TAG_VIEW
                isClickable = true; isFocusable = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            fun dim(a: Float) = shield.setBackgroundColor(android.graphics.Color.argb((a * 255).toInt().coerceIn(0, 255), 26, 22, 12))
            val card = LayoutInflater.from(activity).inflate(R.layout.view_opening_prompt, shield, false)
            val dp = activity.resources.displayMetrics.density
            card.layoutParams = FrameLayout.LayoutParams(
                (292 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END
            ).also { it.marginEnd = (14 * dp).toInt(); it.bottomMargin = (84 * dp).toInt() }

            // Pop out like a bubble, then take it off the screen.
            var closing = false
            fun close(after: () -> Unit = {}) {
                if (closing) return
                closing = true
                runFrames(card, 300L, { t ->
                    val e = t * t
                    card.scaleX = 1f - 0.7f * e; card.scaleY = 1f - 0.7f * e
                    card.alpha = 1f - e
                    dim(0.38f * (1f - e))
                    card.translationY = 12 * dp * e
                }) { host.removeView(shield); after() }
            }
            card.findViewById<View>(R.id.btnGo).setOnClickListener {
                close { OpeningBalanceDialog.show(activity) { } }
            }
            card.findViewById<View>(R.id.btnSkip).setOnClickListener {
                setSkipped(activity)
                close()
            }
            // Bubble-in from the bottom-right corner: grows with a soft overshoot while fading in.
            card.alpha = 0f
            card.scaleX = 0.2f; card.scaleY = 0.2f
            card.translationY = 24 * dp
            shield.addView(card)
            host.addView(shield, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            card.post {
                card.pivotX = card.width.toFloat()
                card.pivotY = card.height.toFloat()
                runFrames(card, 700L, { t ->
                    val p = easeOutBack(t)
                    card.scaleX = 0.2f + 0.8f * p; card.scaleY = 0.2f + 0.8f * p
                    card.alpha = minOf(1f, t * 2.2f)
                    dim(0.38f * minOf(1f, t * 2.2f))
                    card.translationY = 24 * dp * (1f - p)
                })
            }
        }
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 2.0f; val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    /**
     * Frame-driven animation on the display's own frame clock. It does not depend on the phone's
     * "animator duration scale" setting, so the bubble is always visible and smooth.
     */
    private fun runFrames(view: View, durationMs: Long, onFrame: (Float) -> Unit, onEnd: () -> Unit = {}) {
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val start = System.nanoTime()
        fun step() {
            val t = ((System.nanoTime() - start) / 1_000_000f / durationMs).coerceIn(0f, 1f)
            onFrame(t)
            if (t < 1f && view.isAttachedToWindow) view.postOnAnimation { step() }
            else { view.setLayerType(View.LAYER_TYPE_NONE, null); onEnd() }
        }
        step()
    }
}
