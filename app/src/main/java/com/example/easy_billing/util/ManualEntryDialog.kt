package com.example.easy_billing.util

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.repository.MoneyBook
import com.example.easy_billing.ui.MoneyViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Money that did not come from a bill or a purchase: deposit cash to the bank, withdraw it back,
 * other income, other expense. Each is saved as new Money Book rows; nothing is ever edited.
 *
 * All dialogs use the app's centered champagne dialog design (dialog_add_entry_chooser,
 * dialog_add_entry_form, dialog_reverse_entry). The saving rules are unchanged.
 */
object ManualEntryDialog {

    private enum class Kind { DEPOSIT, WITHDRAW, INCOME, EXPENSE }

    private fun themed(activity: AppCompatActivity, view: View): AlertDialog {
        val d = AlertDialog.Builder(activity).setView(view).create()
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return d
    }

    fun show(activity: AppCompatActivity, onSaved: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_entry_chooser, null)
        val dialog = themed(activity, view)
        fun pick(id: Int, kind: Kind) = view.findViewById<View>(id).setOnClickListener {
            dialog.dismiss(); form(activity, kind, onSaved)
        }
        pick(R.id.optDeposit, Kind.DEPOSIT)
        pick(R.id.optWithdraw, Kind.WITHDRAW)
        pick(R.id.optIncome, Kind.INCOME)
        pick(R.id.optExpense, Kind.EXPENSE)
        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun form(activity: AppCompatActivity, kind: Kind, onSaved: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_entry_form, null)
        val dialog = themed(activity, view)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // ── per-kind look and words ──
        val flBadge = view.findViewById<FrameLayout>(R.id.flBadge)
        val ivBadge = view.findViewById<ImageView>(R.id.ivBadge)
        val (badgeBg, icon, tint) = when (kind) {
            Kind.DEPOSIT -> Triple(R.drawable.bg_mae_ic_green, R.drawable.ic_lc_landmark, "#0F6E56")
            Kind.WITHDRAW -> Triple(R.drawable.bg_mae_ic_amber, R.drawable.ic_lc_landmark, "#8A6526")
            Kind.INCOME -> Triple(R.drawable.bg_mae_ic_green, R.drawable.ic_lc_arrow_down_left, "#0F6E56")
            Kind.EXPENSE -> Triple(R.drawable.bg_mae_ic_rose, R.drawable.ic_lc_arrow_up_right, "#9A2E2E")
        }
        flBadge.setBackgroundResource(badgeBg)
        ivBadge.setImageResource(icon)
        ivBadge.setColorFilter(Color.parseColor(tint))
        val (a, b, sub) = when (kind) {
            Kind.DEPOSIT -> Triple(R.string.mae_dep_a, R.string.mae_dep_b, R.string.mae_dep_sub)
            Kind.WITHDRAW -> Triple(R.string.mae_wd_a, R.string.mae_wd_b, R.string.mae_wd_sub)
            Kind.INCOME -> Triple(R.string.mae_inc_a, R.string.mae_inc_b, R.string.mae_inc_sub)
            Kind.EXPENSE -> Triple(R.string.mae_exp_a, R.string.mae_exp_b, R.string.mae_exp_sub)
        }
        view.findViewById<TextView>(R.id.tvTitleA).setText(a)
        view.findViewById<TextView>(R.id.tvTitleB).apply {
            setText(b); if (kind == Kind.EXPENSE) setTextColor(Color.parseColor("#9A2E2E"))
        }
        view.findViewById<TextView>(R.id.tvSub).setText(sub)

        // ── direction strip for deposit / withdraw ──
        if (kind == Kind.DEPOSIT || kind == Kind.WITHDRAW) {
            view.findViewById<View>(R.id.flowStrip).visibility = View.VISIBLE
            val from = view.findViewById<TextView>(R.id.pillFrom)
            val to = view.findViewById<TextView>(R.id.pillTo)
            val cashFirst = kind == Kind.DEPOSIT
            fun style(t: TextView, cash: Boolean) {
                t.setText(if (cash) R.string.cash else R.string.mae_flow_bank)
                t.setBackgroundResource(if (cash) R.drawable.bg_mae_pill_cash else R.drawable.bg_mae_pill_bank)
                t.setTextColor(Color.parseColor(if (cash) "#8A6526" else "#0F6E56"))
            }
            style(from, cashFirst); style(to, !cashFirst)
        }

        val etAmount = view.findViewById<EditText>(R.id.etAmount)
        val etNote = view.findViewById<EditText>(R.id.etNote)
        val barAmount = view.findViewById<View>(R.id.barAmount)
        val amtError = view.findViewById<View>(R.id.amtError)
        etNote.setHint(when (kind) {
            Kind.DEPOSIT -> R.string.money_note_deposit
            Kind.WITHDRAW -> R.string.money_note_withdraw
            Kind.INCOME -> R.string.money_note_income
            Kind.EXPENSE -> R.string.money_note_expense
        })
        etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                amtError.visibility = View.GONE
                barAmount.setBackgroundResource(R.drawable.bg_batch_edge_bar)
            }
        })

        // ── payment mode chips for income / expense ──
        var mode = MoneyMode.CASH
        if (kind == Kind.INCOME || kind == Kind.EXPENSE) {
            view.findViewById<View>(R.id.modeBlock).visibility = View.VISIBLE
            val chips = mutableListOf<Triple<String, LinearLayout, Pair<View, TextView>>>()
            fun paint() = chips.forEach { (m, box, parts) ->
                val on = m == mode
                box.setBackgroundResource(if (on) R.drawable.bg_mae_chip_on else R.drawable.bg_mae_chip_off)
                parts.first.setBackgroundResource(if (on) R.drawable.bg_mae_radio_on else R.drawable.bg_mae_radio_off)
                parts.second.setTextColor(Color.parseColor(if (on) "#123B31" else "#5B6660"))
                parts.second.typeface = ResourcesCompat.getFont(activity, if (on) R.font.googlesans_semibold else R.font.googlesans_medium)
            }
            val rows = listOf(view.findViewById<LinearLayout>(R.id.modeRow1), view.findViewById<LinearLayout>(R.id.modeRow2))
            MoneyMode.ALL.forEachIndexed { i, m ->
                val dot = View(activity)
                val label = TextView(activity).apply {
                    text = PaymentLinesSheet.modeLabel(activity, m)
                    textSize = 13f
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val box = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(MoneyViews.dp(activity, 12), 0, MoneyViews.dp(activity, 8), 0)
                    isClickable = true; isFocusable = true
                    setOnClickListener { mode = m; paint() }
                    addView(dot, LinearLayout.LayoutParams(MoneyViews.dp(activity, 18), MoneyViews.dp(activity, 18)))
                    addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .also { it.marginStart = MoneyViews.dp(activity, 9) })
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (i % 2 == 0) lp.marginEnd = MoneyViews.dp(activity, 4) else lp.marginStart = MoneyViews.dp(activity, 4)
                rows[i / 2].addView(box, lp)
                chips += Triple(m, box, dot to label)
            }
            paint()
        }

        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            val amt = PaymentSplit.round2(etAmount.text.toString().trim().toDoubleOrNull() ?: 0.0)
            if (amt <= 0.0) {
                amtError.visibility = View.VISIBLE
                barAmount.setBackgroundResource(R.drawable.bg_money_edge_bar_error)
                return@setOnClickListener
            }
            val defaultNote = activity.getString(when (kind) {
                Kind.DEPOSIT -> R.string.money_note_deposit
                Kind.WITHDRAW -> R.string.money_note_withdraw
                Kind.INCOME -> R.string.money_note_income
                Kind.EXPENSE -> R.string.money_note_expense
            })
            val text = etNote.text.toString().trim().ifBlank { defaultNote }
            it.isEnabled = false
            activity.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val db = AppDatabase.getDatabase(activity)
                        val book = MoneyBook(db.moneyEntryDao())
                        db.withTransaction {
                            when (kind) {
                                Kind.DEPOSIT -> book.addTransfer(MoneyMode.CASH, MoneyMode.BANK, amt, text)
                                Kind.WITHDRAW -> book.addTransfer(MoneyMode.BANK, MoneyMode.CASH, amt, text)
                                Kind.INCOME -> book.addManual(MoneyDirection.IN, mode, amt, text)
                                Kind.EXPENSE -> book.addManual(MoneyDirection.OUT, mode, amt, text)
                            }
                        }
                    }.getOrDefault(false)
                }
                if (ok) {
                    Toast.makeText(activity, R.string.money_e_saved, Toast.LENGTH_LONG).show()
                    com.example.easy_billing.sync.SyncCoordinator.get(activity).requestSync()
                    dialog.dismiss()
                    onSaved()
                } else {
                    it.isEnabled = true
                    Toast.makeText(activity, R.string.money_e_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    /** Asks, then writes the opposite rows for the manual entry [sourceId]. */
    fun confirmReverse(activity: AppCompatActivity, sourceId: Int, onDone: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_reverse_entry, null)
        val dialog = themed(activity, view)
        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnReverse).setOnClickListener {
            dialog.dismiss()
            activity.lifecycleScope.launch {
                val n = withContext(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(activity)
                    runCatching {
                        db.withTransaction { MoneyBook(db.moneyEntryDao()).reverseManual(sourceId) }
                    }.getOrDefault(0)
                }
                if (n > 0) {
                    Toast.makeText(activity, R.string.money_rev_done, Toast.LENGTH_LONG).show()
                    com.example.easy_billing.sync.SyncCoordinator.get(activity).requestSync()
                } else Toast.makeText(activity, R.string.money_rev_already, Toast.LENGTH_LONG).show()
                onDone()
            }
        }
        dialog.show()
    }
}
