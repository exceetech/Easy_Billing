package com.example.easy_billing.util

import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
 */
object ManualEntryDialog {

    private enum class Kind { DEPOSIT, WITHDRAW, INCOME, EXPENSE }

    fun show(activity: AppCompatActivity, onSaved: () -> Unit) {
        val labels = arrayOf(
            activity.getString(R.string.money_e_deposit), activity.getString(R.string.money_e_withdraw),
            activity.getString(R.string.money_e_income), activity.getString(R.string.money_e_expense)
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.money_add_entry)
            .setItems(labels) { _, which -> form(activity, Kind.values()[which], labels[which], onSaved) }
            .show()
    }

    private fun form(activity: AppCompatActivity, kind: Kind, title: String, onSaved: () -> Unit) {
        val pad = MoneyViews.dp(activity, 20)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, MoneyViews.dp(activity, 8), pad, 0)
        }
        val amount = EditText(activity).apply {
            setHint(R.string.money_e_amount)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val note = EditText(activity).apply {
            setHint(R.string.money_e_note)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        box.addView(amount); box.addView(note)

        var modeGroup: RadioGroup? = null
        if (kind == Kind.INCOME || kind == Kind.EXPENSE) {
            box.addView(MoneyViews.text(activity, activity.getString(R.string.money_e_mode), 12f, MoneyViews.MUTED)
                .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = MoneyViews.dp(activity, 12) })
            modeGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
            MoneyMode.ALL.forEachIndexed { i, m ->
                modeGroup.addView(RadioButton(activity).apply {
                    id = android.view.View.generateViewId()
                    text = PaymentLinesSheet.modeLabel(activity, m)
                    tag = m
                    isChecked = i == 0
                }, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            box.addView(modeGroup)
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.money_opening_save) { _, _ ->
                val amt = PaymentSplit.round2(amount.text.toString().trim().toDoubleOrNull() ?: 0.0)
                if (amt <= 0.0) {
                    Toast.makeText(activity, R.string.money_e_amount_bad, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val mode = modeGroup?.let { g -> g.findViewById<RadioButton>(g.checkedRadioButtonId)?.tag as? String }
                    ?: MoneyMode.CASH
                val defaultNote = activity.getString(when (kind) {
                    Kind.DEPOSIT -> R.string.money_note_deposit
                    Kind.WITHDRAW -> R.string.money_note_withdraw
                    Kind.INCOME -> R.string.money_note_income
                    Kind.EXPENSE -> R.string.money_note_expense
                })
                val text = note.text.toString().trim().ifBlank { defaultNote }
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
                        onSaved()
                    } else Toast.makeText(activity, R.string.money_e_failed, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    /** Asks, then writes the opposite rows for the manual entry [sourceId]. */
    fun confirmReverse(activity: AppCompatActivity, sourceId: Int, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.money_rev_title)
            .setMessage(R.string.money_rev_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.money_rev_btn) { _, _ ->
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
            .show()
    }
}
