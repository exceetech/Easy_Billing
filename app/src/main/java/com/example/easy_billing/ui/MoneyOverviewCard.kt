package com.example.easy_billing.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
        setBackgroundResource(R.drawable.bg_addp_card)
        val p = MoneyViews.dp(context, 16)
        setPadding(p, p, p, p)
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

    private fun bind(within: MoneyTotals, balance: MoneyTotals, pending: Double, hasOpening: Boolean, to: Long) {
        val ctx = context
        removeAllViews()
        addView(MoneyViews.text(ctx, ctx.getString(R.string.money_title), 17f, MoneyViews.INK, true))

        addView(MoneyViews.tableRow(
            ctx, "", ctx.getString(R.string.money_col_cash), ctx.getString(R.string.money_col_bank),
            ctx.getString(R.string.money_col_total), header = true
        ))
        addView(MoneyViews.tableRow(
            ctx, ctx.getString(R.string.money_row_in),
            MoneyViews.money(ctx, within.inCash), MoneyViews.money(ctx, within.inBank),
            MoneyViews.money(ctx, within.inTotal), color = MoneyViews.GREEN
        ))
        addView(MoneyViews.tableRow(
            ctx, ctx.getString(R.string.money_row_out),
            MoneyViews.money(ctx, within.outCash), MoneyViews.money(ctx, within.outBank),
            MoneyViews.money(ctx, within.outTotal), color = MoneyViews.RED
        ))
        addView(MoneyViews.divider(ctx))
        addView(MoneyViews.tableRow(
            ctx, ctx.getString(R.string.money_row_net),
            MoneyViews.money(ctx, within.cashNet), MoneyViews.money(ctx, within.bankNet),
            MoneyViews.money(ctx, within.net), bold = true
        ))

        // Where the money is, at the end of the period (or right now when the period is still running).
        val caption = if (to > appNow()) ctx.getString(R.string.money_balance_now)
        else ctx.getString(R.string.money_balance_on, AppTime.formatter("dd MMM yyyy").format(java.util.Date(to - 1)))
        addView(MoneyViews.divider(ctx))
        addView(MoneyViews.text(ctx, caption, 11f, MoneyViews.MUTED))
        addView(balanceRow(ctx.getString(R.string.money_pocket), ctx.getString(R.string.money_pocket_hint), balance.cashNet))
        addView(balanceRow(ctx.getString(R.string.money_bank), ctx.getString(R.string.money_bank_hint), balance.bankNet))
        if (balance.cashNet < -0.004 || balance.bankNet < -0.004) {
            addView(MoneyViews.text(ctx, ctx.getString(R.string.money_negative_hint), 11f, MoneyViews.RED))
        }
        if (pending > 0.004) {
            addView(MoneyViews.text(ctx, ctx.getString(R.string.money_pending_upi, MoneyViews.money(ctx, pending)), 12f, "#8A6526")
                .also { (it.layoutParams as LayoutParams).topMargin = MoneyViews.dp(ctx, 8) })
        }

        val buttons = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = MoneyViews.dp(ctx, 10)
            }
        }
        if (!hasOpening) {
            buttons.addView(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = ctx.getString(R.string.money_btn_opening)
                setOnClickListener {
                    val a = ctx as? AppCompatActivity ?: return@setOnClickListener
                    OpeningBalanceDialog.show(a) { scope?.let { s -> load(s, startIso, endIso) } }
                }
            })
        }
        buttons.addView(MaterialButton(ctx).apply {
            text = ctx.getString(R.string.money_btn_statement)
            setOnClickListener {
                ctx.startActivity(Intent(ctx, MoneyStatementActivity::class.java)
                    .putExtra(MoneyStatementActivity.EXTRA_START, startIso)
                    .putExtra(MoneyStatementActivity.EXTRA_END, endIso))
            }
        })
        addView(buttons)
    }

    private fun balanceRow(title: String, hint: String, value: Double): View {
        val ctx = context
        return LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, MoneyViews.dp(ctx, 6), 0, MoneyViews.dp(ctx, 2))
            val left = LinearLayout(ctx).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(MoneyViews.text(ctx, title, 14f, MoneyViews.INK, true))
                addView(MoneyViews.text(ctx, hint, 11f, MoneyViews.MUTED))
            }
            addView(left)
            addView(MoneyViews.text(ctx, MoneyViews.money(ctx, value), 18f, MoneyViews.amountColor(value), true, Gravity.END))
        }
    }
}
