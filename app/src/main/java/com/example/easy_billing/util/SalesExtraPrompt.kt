package com.example.easy_billing.util

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.repository.MoneyBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Payment tracking, sales side: the extra money collected for a debit note (the bill went up).
 *
 * Like [SalesRefundPrompt], a "still to collect" note is saved BEFORE the sheet opens, so closing the sheet
 * never loses it; the amount can be recorded later from the bill with [settleDue].
 */
object SalesExtraPrompt {

    private const val DUE = MoneyReason.SALE_EXTRA_DUE

    fun ask(activity: AppCompatActivity, billId: Int, noteId: Int, amount: Double, onDone: () -> Unit) {
        if (PaymentSplit.round2(amount) <= 0.0) { onDone(); return }
        activity.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(activity)
            val book = MoneyBook(db.moneyEntryDao())
            val ok = withContext(Dispatchers.IO) {
                // Already collected for this very note (double tap / re-open): nothing to ask.
                val done = db.moneyEntryDao().forParent(MoneySource.BILL, billId).any {
                    it.direction == MoneyDirection.IN && it.sourceType == MoneySource.DEBIT_NOTE &&
                        it.sourceId == noteId && it.reason == MoneyReason.SALE_EXTRA
                }
                if (done) return@withContext false
                val bill = db.billDao().getBillById(billId)
                book.openDue(MoneyDirection.IN, DUE, amount, MoneySource.DEBIT_NOTE, noteId,
                    MoneySource.BILL, billId, refText = bill.billNumber.trim())
                true
            }
            if (!ok) { onDone(); return@launch }
            showSheet(activity, billId, noteId, PaymentSplit.round2(amount), onDone)
        }
    }

    /** Records an extra charge that was left to collect earlier (oldest debit note first). */
    fun settleDue(activity: AppCompatActivity, billId: Int, onDone: () -> Unit) {
        activity.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(activity)
            val first = withContext(Dispatchers.IO) {
                db.moneyEntryDao().openDues(MoneySource.BILL, billId).firstOrNull { it.reason == DUE }
            }
            if (first == null) { onDone(); return@launch }
            showSheet(activity, billId, first.sourceId, first.amount, onDone)
        }
    }

    private fun showSheet(activity: AppCompatActivity, billId: Int, noteId: Int, amount: Double, onDone: () -> Unit) {
        activity.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(activity)
            val book = MoneyBook(db.moneyEntryDao())
            val bill = withContext(Dispatchers.IO) { db.billDao().getBillById(billId) }
            val defaultMode = PaymentMethodText.parts(bill.paymentMethod)
                .firstOrNull { it in MoneyMode.COUNTER } ?: MoneyMode.CASH
            PaymentLinesSheet.show(
                activity = activity,
                title = activity.getString(R.string.debit_extra_collect_title),
                total = amount,
                modes = MoneyMode.ALL,
                initial = emptyList(),
                defaultMode = defaultMode,
                onConfirm = { lines ->
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            // Close the due note and write the real rows in ONE transaction: both or neither.
                            db.withTransaction {
                                book.recordLines(
                                    direction = MoneyDirection.IN,
                                    lines = lines,
                                    reason = MoneyReason.SALE_EXTRA,
                                    sourceType = MoneySource.DEBIT_NOTE,
                                    sourceId = noteId,
                                    parentType = MoneySource.BILL,
                                    parentId = billId,
                                    refText = bill.billNumber.trim()
                                )
                                book.closeDues(MoneySource.DEBIT_NOTE, noteId, DUE)
                            }
                        }
                        onDone()
                    }
                },
                onCancel = {
                    // The due note stays: the amount can be recorded from the bill at any time.
                    Toast.makeText(activity, R.string.debit_extra_not_recorded, Toast.LENGTH_LONG).show()
                    onDone()
                }
            )
        }
    }
}
