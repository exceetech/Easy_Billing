package com.example.easy_billing.util

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.repository.CreditAdjustmentRepository.Kind
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
 * Closing the sheet means "not handed back now": nothing is recorded.
 */
object SalesRefundPrompt {

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
                val total = PaymentSplit.round2(paid + maxOf(cashExcess, 0.0))
                if (total <= 0.0) return@withContext null
                val basis = if (paid > 0.0) book.refundBasis(MoneySource.BILL, billId, MoneyDirection.IN) else emptyList()
                val bill = db.billDao().getBillById(billId)
                Triple(paid to total, PaymentSplit.proportional(total, basis), bill.billNumber.trim())
            }
            android.util.Log.d("MoneyFlow", "salesRefund bill=$billId doc=$documentLocalId paidPart=$paidPart cashExcess=$cashExcess plan=${plan?.first}")
            if (plan == null) { onDone(); return@launch }
            val (amounts, initial, billNumber) = plan
            val (paid, total) = amounts

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
                            // The first part is the paid money; anything left is the cash settlement.
                            val (paidLines, settleLines) = PaymentSplit.allocate(lines, paid)
                            book.recordLines(
                                MoneyDirection.OUT, paidLines, MoneyReason.SALE_REFUND, sourceType, documentLocalId,
                                parentType = MoneySource.BILL, parentId = billId, refText = billNumber
                            )
                            book.recordLines(
                                MoneyDirection.OUT, settleLines, MoneyReason.ADVANCE_REFUND, sourceType, documentLocalId,
                                parentType = MoneySource.BILL, parentId = billId, refText = billNumber
                            )
                        }
                        onDone()
                    }
                },
                onCancel = {
                    Toast.makeText(activity, R.string.refund_not_recorded, Toast.LENGTH_LONG).show()
                    onDone()
                }
            )
        }
    }
}
