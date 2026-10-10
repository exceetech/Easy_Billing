package com.example.easy_billing.util

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.repository.CreditAdjustmentRepository.Kind
import com.example.easy_billing.repository.CreditAdjustmentRepository.Source
import com.example.easy_billing.repository.MoneyBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Payment tracking, purchase side: the money that moves with a purchase return, a note or a cancel.
 *
 *  - [askRefund]: the supplier hands money BACK (IN, reason PURCHASE_REFUND). The amount is the part of the
 *    document that was paid in cash/bank (never more than was paid and not yet refunded) plus any
 *    "settle in cash" leftover from the credit question.
 *  - [askPay]: a supplier credit note raised the bill of a purchase that was paid now, so the owner pays the
 *    difference (OUT, reason PURCHASE_PAID).
 *
 * Closing a sheet means "not recorded now": nothing is written.
 */
object PurchaseMoneyPrompt {

    fun askRefund(
        activity: AppCompatActivity,
        source: Source,
        kind: Kind,
        documentLocalId: Int,
        paidPart: Double,
        cashExcess: Double,
        onDone: () -> Unit
    ) {
        activity.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(activity)
            val book = MoneyBook(db.moneyEntryDao())

            val plan = withContext(Dispatchers.IO) {
                val srcType: String
                val parentType: String
                val parentId: Int
                var party = ""
                var ref = ""
                var ceilingPaid = 0.0
                var basis: List<PayLine> = emptyList()
                when (source) {
                    is Source.Purchase -> {
                        val p = db.purchaseDao().getById(source.purchaseId) ?: return@withContext null
                        srcType = if (kind == Kind.PURCHASE_CANCEL) MoneySource.PURCHASE else MoneySource.PURCHASE_NOTE
                        parentType = MoneySource.PURCHASE
                        parentId = source.purchaseId
                        party = p.supplierName
                        ref = p.invoiceNumber.trim()
                        val ceiling = book.refundCeiling(parentType, parentId, MoneyDirection.OUT)
                        ceilingPaid = PaymentSplit.round2(minOf(maxOf(paidPart, 0.0), ceiling))
                        if (ceilingPaid > 0.0) basis = book.refundBasis(parentType, parentId, MoneyDirection.OUT)
                    }
                    is Source.SupplierAccount -> {
                        // Returned straight to the supplier account: no single purchase, so only the
                        // "settle in cash" leftover can be money coming back.
                        srcType = MoneySource.PURCHASE_RETURN
                        parentType = MoneySource.PURCHASE_RETURN
                        parentId = documentLocalId
                        ref = "Return"
                    }
                    else -> return@withContext null
                }
                // Already recorded for this very document (double tap / re-open): nothing to ask.
                val done = db.moneyEntryDao().forParent(parentType, parentId).any {
                    it.direction == MoneyDirection.IN && it.sourceType == srcType && it.sourceId == documentLocalId &&
                        it.reason == MoneyReason.PURCHASE_REFUND
                }
                if (done) return@withContext null
                val total = PaymentSplit.round2(ceilingPaid + maxOf(cashExcess, 0.0))
                if (total <= 0.0) return@withContext null
                Plan(srcType, parentType, parentId, party, ref, total, PaymentSplit.proportional(total, basis))
            }
            android.util.Log.d("MoneyFlow", "purchaseRefund source=$source doc=$documentLocalId paidPart=$paidPart cashExcess=$cashExcess total=${plan?.total}")
            if (plan == null) { onDone(); return@launch }

            PaymentLinesSheet.show(
                activity = activity,
                title = activity.getString(R.string.purchase_refund_sheet_title),
                total = plan.total,
                modes = MoneyMode.ALL,
                initial = plan.initial,
                defaultMode = MoneyMode.CASH,
                onConfirm = { lines ->
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            // One reason for the whole amount, so one call (a mode never repeats).
                            book.recordLines(
                                MoneyDirection.IN, lines, MoneyReason.PURCHASE_REFUND, plan.srcType, documentLocalId,
                                parentType = plan.parentType, parentId = plan.parentId,
                                partyName = plan.party, refText = plan.ref
                            )
                        }
                        onDone()
                    }
                },
                onCancel = {
                    Toast.makeText(activity, R.string.purchase_refund_not_recorded, Toast.LENGTH_LONG).show()
                    onDone()
                }
            )
        }
    }

    fun askPay(
        activity: AppCompatActivity,
        purchaseId: Int,
        documentLocalId: Int,
        amount: Double,
        onDone: () -> Unit
    ) {
        activity.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(activity)
            val book = MoneyBook(db.moneyEntryDao())
            val plan = withContext(Dispatchers.IO) {
                val p = db.purchaseDao().getById(purchaseId) ?: return@withContext null
                if (p.isCredit) return@withContext null // added to the supplier balance instead
                val done = db.moneyEntryDao().forParent(MoneySource.PURCHASE, purchaseId).any {
                    it.direction == MoneyDirection.OUT && it.sourceType == MoneySource.PURCHASE_NOTE &&
                        it.sourceId == documentLocalId && it.reason == MoneyReason.PURCHASE_PAID
                }
                if (done) return@withContext null
                val total = PaymentSplit.round2(amount)
                if (total <= 0.0) return@withContext null
                // Start from how the purchase itself was paid.
                val basis = book.refundBasis(MoneySource.PURCHASE, purchaseId, MoneyDirection.OUT)
                Plan(MoneySource.PURCHASE_NOTE, MoneySource.PURCHASE, purchaseId, p.supplierName,
                    p.invoiceNumber.trim(), total, PaymentSplit.proportional(total, basis))
            }
            if (plan == null) { onDone(); return@launch }

            PaymentLinesSheet.show(
                activity = activity,
                title = activity.getString(R.string.purchase_extra_pay_sheet_title),
                total = plan.total,
                modes = MoneyMode.ALL,
                initial = plan.initial,
                defaultMode = MoneyMode.CASH,
                onConfirm = { lines ->
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            book.recordLines(
                                MoneyDirection.OUT, lines, MoneyReason.PURCHASE_PAID, plan.srcType, documentLocalId,
                                parentType = plan.parentType, parentId = plan.parentId,
                                partyName = plan.party, refText = plan.ref
                            )
                        }
                        onDone()
                    }
                },
                onCancel = {
                    Toast.makeText(activity, R.string.purchase_extra_pay_not_recorded, Toast.LENGTH_LONG).show()
                    onDone()
                }
            )
        }
    }

    private class Plan(
        val srcType: String, val parentType: String, val parentId: Int,
        val party: String, val ref: String, val total: Double, val initial: List<PayLine>
    )
}
