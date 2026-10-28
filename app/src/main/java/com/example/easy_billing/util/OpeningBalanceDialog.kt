package com.example.easy_billing.util

import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.repository.MoneyBook
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time opening balance: what the owner has in hand and in the bank on day one.
 * Saved as OPENING rows in the Money Book, dated the start of today. It can be set only once;
 * a later correction is a manual entry (Phase 7), never an edit.
 *
 * Shown as the full-screen champagne popup used by Add stock / Reduce stock. The saving rules are
 * unchanged: at least one amount above zero, no negatives, and only when no opening balance exists.
 */
object OpeningBalanceDialog {

    fun show(activity: AppCompatActivity, onSaved: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_opening_balance_fullscreen, null)
        val dialog = Dialog(activity, R.style.OpeningBalanceFullScreen)
        dialog.setContentView(view)
        KeyboardInsets.install(dialog.window)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        view.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_back_arrow)
            setNavigationOnClickListener { dialog.dismiss() }
        }

        val etCash = view.findViewById<EditText>(R.id.etCash)
        val etBank = view.findViewById<EditText>(R.id.etBank)
        val barCash = view.findViewById<View>(R.id.barCash)
        val barBank = view.findViewById<View>(R.id.barBank)
        val tvTotal = view.findViewById<TextView>(R.id.tvTotal)
        val tvError = view.findViewById<TextView>(R.id.tvError)
        val btnSave = view.findViewById<Button>(R.id.btnSave)

        fun amount(e: EditText) = PaymentSplit.round2(e.text.toString().trim().toDoubleOrNull() ?: 0.0)

        fun clearError() {
            tvError.visibility = View.GONE
            barCash.setBackgroundResource(R.drawable.bg_lg_amount_bar)
            barBank.setBackgroundResource(R.drawable.bg_lg_amount_bar)
        }

        fun refreshTotal() {
            val c = amount(etCash); val b = amount(etBank)
            val total = PaymentSplit.round2(maxOf(c, 0.0) + maxOf(b, 0.0))
            tvTotal.text = CurrencyHelper.format(activity, total)
            tvTotal.setTextColor(android.graphics.Color.parseColor(if (total > 0.0) "#0F6E56" else "#7C8782"))
        }
        refreshTotal()

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { clearError(); refreshTotal() }
        }
        etCash.addTextChangedListener(watcher)
        etBank.addTextChangedListener(watcher)

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val c = amount(etCash)
            val b = amount(etBank)
            if (c < 0.0 || b < 0.0 || (c <= 0.0 && b <= 0.0)) {
                tvError.visibility = View.VISIBLE
                barCash.setBackgroundResource(R.drawable.bg_money_edge_bar_error)
                barBank.setBackgroundResource(R.drawable.bg_money_edge_bar_error)
                return@setOnClickListener
            }
            btnSave.isEnabled = false
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val db = AppDatabase.getDatabase(activity)
                        val dao = db.moneyEntryDao()
                        db.withTransaction {
                            if (dao.countActiveOpening() > 0) return@withTransaction false
                            val sid = dao.nextManualSourceId()
                            val at = MoneyReport.startOfToday()
                            val book = MoneyBook(dao)
                            if (c > 0.0) book.record(MoneyDirection.IN, MoneyMode.CASH, c, MoneyReason.OPENING,
                                MoneySource.MANUAL, sid, at = at)
                            if (b > 0.0) book.record(MoneyDirection.IN, MoneyMode.BANK, b, MoneyReason.OPENING,
                                MoneySource.MANUAL, sid, at = at)
                            true
                        }
                    }
                }
                when {
                    result.isFailure -> {
                        btnSave.isEnabled = true
                        Toast.makeText(activity, R.string.money_opening_failed, Toast.LENGTH_LONG).show()
                    }
                    result.getOrNull() == true -> {
                        Toast.makeText(activity, R.string.money_opening_done, Toast.LENGTH_LONG).show()
                        com.example.easy_billing.sync.SyncCoordinator.get(activity).requestSync()
                        dialog.dismiss()
                        onSaved()
                    }
                    else -> {
                        Toast.makeText(activity, R.string.money_opening_exists, Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
    }
}
