package com.example.easy_billing.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.easy_billing.MoneyStatementActivity
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.MoneyReport
import com.example.easy_billing.util.MoneyTotals
import com.example.easy_billing.util.OpeningBalanceDialog
import com.example.easy_billing.util.appNow
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Money card on the Reports Overview tab. Follows the selected period:
 * money in / out / net split into cash and bank, plus what is in the pocket and in the bank at the end of it.
 * Everything is worked out on the phone from the Money Book.
 */
class MoneyOverviewCard(context: Context) : LinearLayout(context) {

    private var job: Job? = null
    private var startIso = ""
    private var endIso = ""
    private var scope: CoroutineScope? = null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_lg_card)
        clipToOutline = true // keeps the header band inside the rounded corners on every Android version
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = MoneyViews.dp(context, 14)
        }
    }

    fun load(scope: CoroutineScope, startIso: String, endIso: String) {
        this.scope = scope; this.startIso = startIso; this.endIso = endIso
        job?.cancel()
        job = scope.launch {
            val ctx = context
            val data = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(ctx).moneyEntryDao()
                val (from, to) = MoneyReport.dayBounds(startIso, endIso)
                // Earnings figures leave the opening balance out; balances include it.
                val within = MoneyReport.totals(dao.sumsBetween(from, to, listOf("OPENING", "TRANSFER")))
                val upToEnd = MoneyReport.totals(dao.sumsBetween(0L, to, emptyList()))
                Triple(within, upToEnd, Triple(dao.pendingInTotal(), dao.countActiveOpening() > 0, to))
            }
            val (within, balance, extra) = data
            bind(within, balance, extra.first, extra.second, extra.third)
        }
    }

    // ── look: same family as the Overview "story" card (champagne, mint header, serif numbers) ──
    private val GREEN = "#0F6E56"
    private val GREEN_D = "#085041"
    private val RED = "#791F1F"
    private val BLUE = "#185FA5"
    private val AMBER = "#8A6526"

    private fun semibold(): Typeface? = try { ResourcesCompat.getFont(context, R.font.googlesans_semibold) } catch (_: Exception) { null }
    private fun dp(v: Int) = MoneyViews.dp(context, v)

    private fun tv(s: CharSequence, sp: Float, color: String, serif: Boolean = false, semi: Boolean = false): TextView =
        TextView(context).apply {
            text = s; textSize = sp; setTextColor(Color.parseColor(color))
            if (serif) typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            else if (semi) semibold()?.let { typeface = it }
            includeFontPadding = false
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f) =
        if (weight > 0f) LayoutParams(w, h, weight) else LayoutParams(w, h)

    private fun icon(res: Int, tint: String, size: Int) = ImageView(context).apply {
        setImageResource(res); setColorFilter(Color.parseColor(tint))
        layoutParams = lp(dp(size), dp(size)); contentDescription = null
    }

    private fun periodText(): String = try {
        val f = AppTime.isoDate(); val o = AppTime.formatter("dd MMM")
        val a = o.format(f.parse(startIso)!!); val b = o.format(f.parse(endIso)!!)
        if (startIso == endIso) a else "$a – $b"
    } catch (_: Exception) { "" }

    private fun bind(within: MoneyTotals, balance: MoneyTotals, pending: Double, hasOpening: Boolean, to: Long) {
        val ctx = context
        removeAllViews()
        val live = to > appNow()
        val negative = balance.cashNet < -0.004 || balance.bankNet < -0.004
        val total = balance.net

        // Header
        addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_lg_hdr_amber)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(FrameLayout(ctx).apply {
                layoutParams = lp(dp(28), dp(28))
                addView(View(ctx).apply { setBackgroundResource(R.drawable.bg_step_circle_ring_champagne); layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)) })
                addView(icon(R.drawable.ic_lc_indian_rupee, "#FFFFFF", 14).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)
                })
            })
            addView(LinearLayout(ctx).apply {
                orientation = VERTICAL
                layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(10) }
                addView(tv(ctx.getString(R.string.money_title), 15f, "#4A3A16", semi = true))
                addView(tv(ctx.getString(R.string.money_subtitle), 11f, AMBER).also { (it.layoutParams as? LayoutParams)?.topMargin = dp(2) })
            })
            val per = periodText()
            if (per.isNotEmpty()) addView(tv(per, 11f, AMBER).apply {
                setBackgroundResource(R.drawable.bg_money_pill)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            })
        })

        // Hero: one big answer
        addView(LinearLayout(ctx).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(18), dp(16), dp(4))
            addView(tv(
                if (live) ctx.getString(R.string.money_hero_now)
                else ctx.getString(R.string.money_hero_on, AppTime.formatter("dd MMM yyyy").format(java.util.Date(to - 1))),
                13f, "#5B6660"))
            addView(tv(MoneyViews.money(ctx, total), 38f, if (total < -0.004) RED else GREEN, serif = true).apply {
                gravity = Gravity.CENTER; maxLines = 1
                layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) }
            })
        })

        // Pocket and bank tiles
        addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
            addView(tile(ctx.getString(R.string.money_pocket), ctx.getString(R.string.money_pocket_hint), balance.cashNet, AMBER,
                Modifier.START))
            addView(tile(ctx.getString(R.string.money_bank), ctx.getString(R.string.money_bank_hint), balance.bankNet, BLUE,
                Modifier.END))
        })

        // This period: money in / out, each with its cash and bank part
        addView(tv(ctx.getString(R.string.money_this_period).uppercase(), 11f, MoneyViews.MUTED, semi = true).apply {
            letterSpacing = 0.06f
            layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(14); it.marginStart = dp(16); it.bottomMargin = dp(6)
            }
        })
        addView(LinearLayout(ctx).apply {
            orientation = VERTICAL
            setBackgroundResource(R.drawable.bg_money_flow)
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(16); it.marginEnd = dp(16)
            }
            addView(flowRow(true, ctx.getString(R.string.money_row_in), within.inCash, within.inBank, within.inTotal))
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#E3E8E4"))
                layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
            addView(flowRow(false, ctx.getString(R.string.money_row_out), within.outCash, within.outBank, within.outTotal))
        })

        // Kept this period
        val net = within.net
        addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(if (net < -0.004) R.drawable.bg_money_net_neg else R.drawable.bg_money_net_pos)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(10); it.marginStart = dp(16); it.marginEnd = dp(16)
            }
            val ink = if (net < -0.004) RED else "#123B31"
            addView(tv(ctx.getString(R.string.money_kept), 13f, ink, semi = true).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(tv(MoneyViews.money(ctx, net), 19f, if (net < -0.004) RED else GREEN_D, serif = true))
        })

        if (pending > 0.004) addView(band(R.drawable.bg_money_band_amber, R.drawable.ic_lc_clock, AMBER,
            ctx.getString(R.string.money_pending_upi, MoneyViews.money(ctx, pending))))
        if (negative) addView(band(R.drawable.bg_money_band_red, R.drawable.ic_lc_alert_triangle, RED,
            ctx.getString(R.string.money_negative_hint)))

        // Buttons (same actions as before): two soft pills, or one wide "View statement" pill
        val buttons = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        fun pill(bg: Int, ic: Int, color: String, label: String, chevron: Boolean, weight: Float, onClick: () -> Unit): View =
            LinearLayout(ctx).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER
                setBackgroundResource(bg)
                setPadding(dp(8), 0, dp(8), 0)
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
                addView(icon(ic, color, 16).apply { (layoutParams as LayoutParams).marginEnd = dp(7) })
                addView(tv(label, 12.5f, color, semi = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (chevron) addView(icon(R.drawable.ic_lc_chevron_right, color, 15).apply { (layoutParams as LayoutParams).marginStart = dp(4) })
                layoutParams = lp(0, dp(40), weight)
            }
        val openStatement = {
            ctx.startActivity(Intent(ctx, MoneyStatementActivity::class.java)
                .putExtra(MoneyStatementActivity.EXTRA_START, startIso)
                .putExtra(MoneyStatementActivity.EXTRA_END, endIso))
        }
        if (!hasOpening) {
            buttons.addView(pill(R.drawable.bg_money_btn_gold_outline, R.drawable.ic_lc_scale, AMBER,
                ctx.getString(R.string.money_btn_opening), false, 1.25f) {
                val a = ctx as? AppCompatActivity ?: return@pill
                OpeningBalanceDialog.show(a) { scope?.let { s -> load(s, startIso, endIso) } }
            })
            buttons.addView(pill(R.drawable.bg_lg_cta_green_12, R.drawable.ic_lc_file_text, "#FFFFFF",
                ctx.getString(R.string.money_btn_statement), false, 1f, openStatement).apply {
                (layoutParams as LayoutParams).marginStart = dp(9)
            })
        } else {
            buttons.addView(pill(R.drawable.bg_lg_cta_green_12, R.drawable.ic_lc_file_text, "#FFFFFF",
                ctx.getString(R.string.money_btn_statement_view), true, 1f, openStatement))
        }
        addView(buttons)
    }

    private enum class Modifier { START, END }

    private fun tile(title: String, hint: String, value: Double, dot: String, side: Modifier): View {
        val neg = value < -0.004
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundResource(if (neg) R.drawable.bg_money_tile_neg else R.drawable.bg_money_tile)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                if (side == Modifier.START) it.marginEnd = dp(5) else it.marginStart = dp(5)
            }
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(View(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.parseColor(if (neg) RED else dot))
                    }
                    layoutParams = lp(dp(8), dp(8)).also { it.marginEnd = dp(6) }
                })
                addView(tv(title, 12f, MoneyViews.INK, semi = true))
            })
            addView(tv(MoneyViews.money(context, value), 20f, if (neg) RED else MoneyViews.INK, serif = true).apply {
                maxLines = 1
                layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(5) }
            })
            addView(tv(hint, 10.5f, MoneyViews.MUTED).apply {
                layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(1) }
            })
        }
    }

    private fun flowRow(isIn: Boolean, title: String, cash: Double, bank: Double, total: Double): View {
        val ctx = context
        val tint = if (isIn) GREEN else RED
        return LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            addView(FrameLayout(ctx).apply {
                setBackgroundResource(if (isIn) R.drawable.bg_money_ic_in else R.drawable.bg_money_ic_out)
                layoutParams = lp(dp(30), dp(30))
                addView(icon(if (isIn) R.drawable.ic_lc_arrow_down_left else R.drawable.ic_lc_arrow_up_right, tint, 15).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER)
                })
            })
            addView(LinearLayout(ctx).apply {
                orientation = VERTICAL
                layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(10); it.marginEnd = dp(8) }
                addView(tv(title, 13.5f, MoneyViews.INK, semi = true))
                addView(LinearLayout(ctx).apply {
                    orientation = HORIZONTAL
                    layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(5) }
                    addView(chip(ctx.getString(R.string.money_col_cash) + " " + MoneyViews.money(ctx, cash), true))
                    addView(chip(ctx.getString(R.string.money_col_bank) + " " + MoneyViews.money(ctx, bank), false))
                })
            })
            addView(tv(MoneyViews.money(ctx, total), 16f, tint, serif = true).apply { maxLines = 1 })
        }
    }

    private fun chip(text: String, first: Boolean): View = tv(text, 10.5f, "#5B6660").apply {
        setBackgroundResource(R.drawable.bg_money_chip)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        maxLines = 1
        layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { if (!first) it.marginStart = dp(6) }
    }

    private fun band(bg: Int, ic: Int, color: String, msg: String): View = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(bg)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(10); it.marginStart = dp(16); it.marginEnd = dp(16)
        }
        addView(icon(ic, color, 15).apply { (layoutParams as LayoutParams).marginEnd = dp(8) })
        addView(tv(msg, 12f, color).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
    }
}
