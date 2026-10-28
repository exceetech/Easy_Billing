package com.example.easy_billing.util

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
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
 * Closing a sheet leaves a "still due" note on the purchase (it is not money); the amount is recorded later
 * with [settleDue].
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
                        // A return made from the Inventory page has its own document ids (return row ids), so it gets its own
                        // source type; a purchase note keeps PURCHASE_NOTE. Ids of different types never collide.
                        srcType = when (kind) {
                            Kind.PURCHASE_CANCEL -> MoneySource.PURCHASE
                            Kind.PURCHASE_RETURN -> MoneySource.PURCHASE_RETURN
                            else -> MoneySource.PURCHASE_NOTE
                        }
                        parentType = MoneySource.PURCHASE
                        parentId = source.purchaseId
                        party = p.supplierName
                        ref = p.invoiceNumber.trim()
                        val ceiling = book.refundCeiling(parentType, parentId, MoneyDirection.OUT)
                        ceilingPaid = PaymentSplit.round2(minOf(maxOf(paidPart, 0.0), ceiling))
                        // A return from the Inventory page of a purchase that was paid before payments were tracked: it has no
                        // money rows at all, so there is no ceiling to read. It was not on credit, so what the supplier gives
                        // back is the returned value; the owner confirms the amount and modes on the sheet.
                        if (kind == Kind.PURCHASE_RETURN && !p.isCredit && ceilingPaid <= 0.0 && paidPart > 0.0 &&
                            db.moneyEntryDao().sumForParentReasons(
                                parentType, parentId, MoneyDirection.OUT, listOf(MoneyReason.PURCHASE_PAID)
                            ) <= 0.0 &&
                            db.moneyEntryDao().forParent(parentType, parentId).none { it.reason == MoneyReason.PURCHASE_PAID }
                        ) ceilingPaid = PaymentSplit.round2(paidPart)
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
            // Save the "supplier refund still due" note BEFORE the sheet opens (a purchase only: a return booked
            // straight on a supplier account has no purchase screen to show it on).
            if (source is Source.Purchase) withContext(Dispatchers.IO) {
                book.openDue(MoneyDirection.IN, MoneyReason.PURCHASE_REFUND_DUE, plan.total, plan.srcType, documentLocalId,
                    plan.parentType, plan.parentId, plan.party, plan.ref)
            }
            // Inventory batch return across several invoices: the owner already said how the supplier refunded
            // (first sheet), so the next invoices reuse those modes in the same proportions — no second sheet.
            val shared = BatchRefundSession.modes
            if (shared != null && documentLocalId in BatchRefundSession.docs) {
                val lines = PaymentSplit.proportional(plan.total, shared)
                if (lines.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(activity).withTransaction {
                            book.recordLines(
                                MoneyDirection.IN, lines, MoneyReason.PURCHASE_REFUND, plan.srcType, documentLocalId,
                                parentType = plan.parentType, parentId = plan.parentId,
                                partyName = plan.party, refText = plan.ref
                            )
                            book.closeDues(plan.srcType, documentLocalId, MoneyReason.PURCHASE_REFUND_DUE)
                        }
                    }
                    onDone()
                    return@launch
                }
            }
            showRefundSheet(activity, plan, documentLocalId, onDone)
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
            // Save the "payment to supplier still due" note BEFORE the sheet opens.
            withContext(Dispatchers.IO) {
                book.openDue(MoneyDirection.OUT, MoneyReason.PURCHASE_PAID_DUE, plan.total, plan.srcType, documentLocalId,
                    plan.parentType, plan.parentId, plan.party, plan.ref)
            }
            showPaySheet(activity, plan, documentLocalId, onDone)
        }
    }

    /** Total still due on a purchase: (money to receive, money to pay). Used by the Purchase details banners. */
    suspend fun dueTotals(activity: AppCompatActivity, purchaseId: Int): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val dues = AppDatabase.getDatabase(activity).moneyEntryDao().openDues(MoneySource.PURCHASE, purchaseId)
        dues.filter { it.reason == MoneyReason.PURCHASE_REFUND_DUE }.sumOf { it.amount } to
            dues.filter { it.reason == MoneyReason.PURCHASE_PAID_DUE }.sumOf { it.amount }
    }

    /**
     * Records a supplier refund / extra payment that was left due earlier (oldest first). The caller refreshes
     * its banners in [onDone], which then show anything that is left.
     */
    fun settleDue(activity: AppCompatActivity, purchaseId: Int, onDone: () -> Unit) {
        activity.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(activity)
            val book = MoneyBook(db.moneyEntryDao())
            val prepared = withContext(Dispatchers.IO) {
                val first = db.moneyEntryDao().openDues(MoneySource.PURCHASE, purchaseId).firstOrNull()
                    ?: return@withContext null
                val basis = book.refundBasis(MoneySource.PURCHASE, purchaseId, MoneyDirection.OUT)
                var total = PaymentSplit.round2(first.amount)
                // A supplier refund can never exceed what was really paid and not yet refunded (same rule as the
                // sales side). Skipped for a purchase from before payments were tracked: it has no paid rows, so there is
                // no ceiling to read, and the owner confirms the amount on the sheet.
                if (first.reason == MoneyReason.PURCHASE_REFUND_DUE &&
                    db.moneyEntryDao().forParent(MoneySource.PURCHASE, purchaseId).any { it.reason == MoneyReason.PURCHASE_PAID }
                ) {
                    val ceiling = book.refundCeiling(MoneySource.PURCHASE, purchaseId, MoneyDirection.OUT)
                    total = PaymentSplit.round2(minOf(total, ceiling))
                    if (total <= 0.0) {
                        // Everything that could be refunded has been already: nothing is due any more.
                        book.closeDues(first.sourceType, first.sourceId, MoneyReason.PURCHASE_REFUND_DUE)
                        return@withContext null
                    }
                }
                first to Plan(first.sourceType, first.parentType, first.parentId, first.partyName, first.refText,
                    total, PaymentSplit.proportional(total, basis))
            }
            if (prepared == null) { onDone(); return@launch }
            val (note, plan) = prepared
            if (note.reason == MoneyReason.PURCHASE_REFUND_DUE) showRefundSheet(activity, plan, note.sourceId, onDone)
            else showPaySheet(activity, plan, note.sourceId, onDone)
        }
    }

    private fun showRefundSheet(activity: AppCompatActivity, plan: Plan, documentLocalId: Int, onDone: () -> Unit) {
        val db = AppDatabase.getDatabase(activity)
        val book = MoneyBook(db.moneyEntryDao())
        PaymentLinesSheet.show(
            activity = activity,
            title = activity.getString(R.string.purchase_refund_sheet_title),
            total = plan.total,
            modes = MoneyMode.ALL,
            initial = plan.initial,
            defaultMode = MoneyMode.CASH,
            onConfirm = { lines ->
                if (documentLocalId in BatchRefundSession.docs) BatchRefundSession.modes = lines
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // Close the due note and write the real rows in ONE transaction: both or neither.
                        db.withTransaction {
                            // One reason for the whole amount, so one call (a mode never repeats).
                            book.recordLines(
                                MoneyDirection.IN, lines, MoneyReason.PURCHASE_REFUND, plan.srcType, documentLocalId,
                                parentType = plan.parentType, parentId = plan.parentId,
                                partyName = plan.party, refText = plan.ref
                            )
                            book.closeDues(plan.srcType, documentLocalId, MoneyReason.PURCHASE_REFUND_DUE)
                        }
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

    private fun showPaySheet(activity: AppCompatActivity, plan: Plan, documentLocalId: Int, onDone: () -> Unit) {
        val db = AppDatabase.getDatabase(activity)
        val book = MoneyBook(db.moneyEntryDao())
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
                        db.withTransaction {
                            book.recordLines(
                                MoneyDirection.OUT, lines, MoneyReason.PURCHASE_PAID, plan.srcType, documentLocalId,
                                parentType = plan.parentType, parentId = plan.parentId,
                                partyName = plan.party, refText = plan.ref
                            )
                            book.closeDues(plan.srcType, documentLocalId, MoneyReason.PURCHASE_PAID_DUE)
                        }
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

    private class Plan(
        val srcType: String, val parentType: String, val parentId: Int,
        val party: String, val ref: String, val total: Double, val initial: List<PayLine>
    )
}

/**
 * Only used while the Inventory page settles a return that spans several purchase invoices: the first refund
 * sheet's modes are reused for the rest. [docs] lists that run's own document ids, so nothing else is affected.
 */
object BatchRefundSession {
    @Volatile var docs: Set<Int> = emptySet()
    @Volatile var modes: List<PayLine>? = null
    fun end() { docs = emptySet(); modes = null }
}
