package com.example.easy_billing.util

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.repository.CreditAdjustmentRepository.Kind
import androidx.room.withTransaction
import com.example.easy_billing.repository.MoneyBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Payment tracking, sales side: the refund that goes with a sales return or a cancelled bill.
 *
 * Money to hand back = [paidPart] (the part of the document that was NOT on the customer's credit
 * account, never more than what was actually received for the bill and not yet refunded)
 * + [cashExcess] (a "settle in cash" leftover from the credit question).
 *
 * The sheet opens with the refund split the same way the bill was paid; the owner can change it.
 * Closing the sheet leaves a "refund still due" note on the bill (nothing is counted as money); the refund is
 * recorded later with [settleDue].
 */
object SalesRefundPrompt {

    private val DUE_REASONS = arrayOf(MoneyReason.SALE_REFUND_DUE, MoneyReason.ADVANCE_REFUND_DUE)

    fun ask(
        activity: AppCompatActivity,
        billId: Int,
        kind: Kind,
        documentLocalId: Int,
        paidPart: Double,
        cashExcess: Double,
        onDone: () -> Unit
    ) {
        if (kind != Kind.SALE_RETURN && kind != Kind.BILL_CANCEL) { onDone(); return }
        activity.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(activity)
            val book = MoneyBook(db.moneyEntryDao())
            val sourceType = if (kind == Kind.SALE_RETURN) MoneySource.CREDIT_NOTE else MoneySource.BILL

            val plan = withContext(Dispatchers.IO) {
                // Already refunded for this very document (double tap / re-open): nothing to ask.
                val done = db.moneyEntryDao().forParent(MoneySource.BILL, billId).any {
                    it.direction == MoneyDirection.OUT && it.sourceType == sourceType && it.sourceId == documentLocalId &&
                        (it.reason == MoneyReason.SALE_REFUND || it.reason == MoneyReason.ADVANCE_REFUND)
                }
                if (done) return@withContext null
                val ceiling = book.refundCeiling(MoneySource.BILL, billId, MoneyDirection.IN)
                val paid = PaymentSplit.round2(minOf(maxOf(paidPart, 0.0), ceiling))
                val settle = PaymentSplit.round2(maxOf(cashExcess, 0.0))
                if (paid + settle <= 0.0) return@withContext null
                val bill = db.billDao().getBillById(billId)
                // Save the "refund still due" note BEFORE the sheet opens. Closing the sheet, pressing back or the
                // app being killed can then never lose the refund: it stays on the bill until it is recorded.
                withContext(Dispatchers.IO) {
                    book.openDue(MoneyDirection.OUT, MoneyReason.SALE_REFUND_DUE, paid, sourceType, documentLocalId,
                        MoneySource.BILL, billId, refText = bill.billNumber.trim())
                    book.openDue(MoneyDirection.OUT, MoneyReason.ADVANCE_REFUND_DUE, settle, sourceType, documentLocalId,
                        MoneySource.BILL, billId, refText = bill.billNumber.trim())
                }
                Due(sourceType, documentLocalId, paid, settle, bill.billNumber.trim())
            }
            android.util.Log.d("MoneyFlow", "salesRefund bill=$billId doc=$documentLocalId paidPart=$paidPart cashExcess=$cashExcess plan=${plan?.paid}/${plan?.settle}")
            if (plan == null) { onDone(); return@launch }
            showSheet(activity, billId, plan, onDone)
        }
    }

    /** Total of the refunds still due on a bill (0 when none). Used by the "Record refund" banner. */
    suspend fun dueTotal(activity: AppCompatActivity, billId: Int): Double = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(activity).moneyEntryDao().openDues(MoneySource.BILL, billId)
            .filter { it.reason in DUE_REASONS }.sumOf { it.amount }
    }

    /**
     * Records a refund that was left due earlier (the sheet was closed). Handles the oldest document first;
     * the caller refreshes its banner in [onDone], which shows any that are left.
     * The amount is checked again against what was really received and not yet refunded.
     */
    fun settleDue(activity: AppCompatActivity, billId: Int, onDone: () -> Unit) {
        activity.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(activity)
            val book = MoneyBook(db.moneyEntryDao())
            val plan = withContext(Dispatchers.IO) {
                val notes = db.moneyEntryDao().openDues(MoneySource.BILL, billId).filter { it.reason in DUE_REASONS }
                val first = notes.firstOrNull() ?: return@withContext null
                val group = notes.filter { it.sourceType == first.sourceType && it.sourceId == first.sourceId }
                val ceiling = book.refundCeiling(MoneySource.BILL, billId, MoneyDirection.IN)
                val paid = PaymentSplit.round2(minOf(group.filter { it.reason == MoneyReason.SALE_REFUND_DUE }.sumOf { it.amount }, ceiling))
                val settle = PaymentSplit.round2(group.filter { it.reason == MoneyReason.ADVANCE_REFUND_DUE }.sumOf { it.amount })
                if (paid + settle <= 0.0) {
                    // Everything that could be refunded has been already: nothing is due any more.
                    book.closeDues(first.sourceType, first.sourceId, *DUE_REASONS)
                    return@withContext Due(first.sourceType, first.sourceId, 0.0, 0.0, first.refText)
                }
                Due(first.sourceType, first.sourceId, paid, settle, first.refText)
            }
            if (plan == null) { onDone(); return@launch }
            if (plan.paid + plan.settle <= 0.0) {
                Toast.makeText(activity, R.string.due_nothing_left, Toast.LENGTH_LONG).show()
                onDone(); return@launch
            }
            showSheet(activity, billId, plan, onDone)
        }
    }

    private class Due(val sourceType: String, val docId: Int, val paid: Double, val settle: Double, val billNumber: String)

    private fun showSheet(activity: AppCompatActivity, billId: Int, plan: Due, onDone: () -> Unit) {
        activity.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(activity)
            val book = MoneyBook(db.moneyEntryDao())
            val total = PaymentSplit.round2(plan.paid + plan.settle)
            val initial = withContext(Dispatchers.IO) {
                // The refund starts split the same way the bill was paid; the owner can change it.
                val basis = if (plan.paid > 0.0) book.refundBasis(MoneySource.BILL, billId, MoneyDirection.IN) else emptyList()
                PaymentSplit.proportional(total, basis)
            }
            PaymentLinesSheet.show(
                activity = activity,
                title = activity.getString(R.string.refund_sheet_title),
                total = total,
                modes = MoneyMode.ALL,
                initial = initial,
                defaultMode = MoneyMode.CASH,
                onConfirm = { lines ->
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            // Close the due note and write the real rows in ONE transaction: both or neither.
                            db.withTransaction {
                                // The first part is the paid money; anything left is the cash settlement.
                                val (paidLines, settleLines) = PaymentSplit.allocate(lines, plan.paid)
                                book.recordLines(
                                    MoneyDirection.OUT, paidLines, MoneyReason.SALE_REFUND, plan.sourceType, plan.docId,
                                    parentType = MoneySource.BILL, parentId = billId, refText = plan.billNumber
                                )
                                book.recordLines(
                                    MoneyDirection.OUT, settleLines, MoneyReason.ADVANCE_REFUND, plan.sourceType, plan.docId,
                                    parentType = MoneySource.BILL, parentId = billId, refText = plan.billNumber
                                )
                                book.closeDues(plan.sourceType, plan.docId, *DUE_REASONS)
                                // A cancelled bill hands back ALL the money it still holds, so once nothing is
                                // left, no earlier "refund still due" note on this bill is wanted any more.
                                if (plan.sourceType == MoneySource.BILL &&
                                    book.refundCeiling(MoneySource.BILL, billId, MoneyDirection.IN) <= 0.004) {
                                    db.moneyEntryDao().closeDuesForParent(MoneySource.BILL, billId, listOf(MoneyReason.SALE_REFUND_DUE))
                                }
                            }
                        }
                        onDone()
                    }
                },
                onCancel = {
                    // The due note stays: the refund can be recorded from the bill at any time.
                    Toast.makeText(activity, R.string.refund_not_recorded, Toast.LENGTH_LONG).show()
                    onDone()
                }
            )
        }
    }
}
