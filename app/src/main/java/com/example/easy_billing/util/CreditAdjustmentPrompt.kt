package com.example.easy_billing.util

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.R
import com.example.easy_billing.repository.CreditAdjustmentRepository
import com.example.easy_billing.repository.CreditAdjustmentRepository.Kind
import com.example.easy_billing.repository.CreditAdjustmentRepository.Plan
import com.example.easy_billing.repository.CreditAdjustmentRepository.PostResult
import com.example.easy_billing.repository.CreditAdjustmentRepository.Source
import kotlinx.coroutines.launch

/**
 * Applies a bill/purchase adjustment to a credit balance, asking the owner only
 * when there is a real choice to make.
 *
 * A return first pays down what is owed; that part happens silently. The owner
 * is asked one question only when a return overshoots the debt — the leftover
 * is either cash or an advance. A charge (sales debit note / supplier credit
 * note) always just adds. A cash bill/purchase triggers nothing.
 *
 * Sales and purchase share this one dialog by passing a [Source]. [onDone]
 * always runs at the end so the caller can finish its own flow.
 */
object CreditAdjustmentPrompt {

    private fun money(context: android.content.Context, v: Double): String {
        val symbol = CurrencyHelper.getCurrencySymbol(context)
        return if (v % 1.0 == 0.0) "$symbol${v.toLong()}" else "$symbol${"%.2f".format(v)}"
    }

    /** Sales entry point — unchanged signature. */
    fun handle(
        activity: AppCompatActivity,
        billId: Int,
        kind: Kind,
        amount: Double,
        documentLocalId: Int,
        onDone: () -> Unit
    ) = handleSource(activity, Source.Bill(billId), kind, amount, documentLocalId, onDone)

    /** Purchase entry point. */
    fun handlePurchase(
        activity: AppCompatActivity,
        purchaseId: Int,
        kind: Kind,
        amount: Double,
        documentLocalId: Int,
        onDone: () -> Unit
    ) {
        // Written down first and struck off at the end, so an app killed in between can finish it later
        // (see [replayPendingPurchases]). Inventory supplier returns keep their own list, so they are skipped here.
        val shop = activity.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE).getInt("SHOP_ID", -1)
        val tracked = kind != Kind.PURCHASE_RETURN
        if (tracked) PendingPurchaseAdjustments.remember(activity, shop, purchaseId, kind.name, amount, documentLocalId)
        handleSource(activity, Source.Purchase(purchaseId), kind, amount, documentLocalId) {
            if (tracked) PendingPurchaseAdjustments.forget(activity, shop, purchaseId, kind.name, documentLocalId)
            onDone()
        }
    }

    @Volatile private var replaying = false

    /**
     * Finishes purchase-document money steps that an earlier session never completed. Only a step with NOTHING written
     * yet is run again (no ledger row and no refund / due note for that document); anything else was already under
     * way and is covered by its own "still due" note. Call when a purchase screen opens; [onAllDone] runs at the end.
     */
    fun replayPendingPurchases(activity: AppCompatActivity, onAllDone: () -> Unit = {}) {
        if (replaying) return
        val shop = activity.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE).getInt("SHOP_ID", -1)
        val items = PendingPurchaseAdjustments.forShop(activity, shop)
        if (items.isEmpty()) return
        replaying = true
        activity.lifecycleScope.launch {
            fun finish() { replaying = false; onAllDone() }
            fun next(i: Int) {
                if (i >= items.size || activity.isFinishing) { finish(); return }
                val item = items[i]
                val kind = try { Kind.valueOf(item.kind) } catch (_: Exception) { null }
                activity.lifecycleScope.launch {
                    val started = kind == null || kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val db = com.example.easy_billing.db.AppDatabase.getDatabase(activity)
                            val ledger = db.creditTransactionDao()
                                .getForDoc(CreditAdjustmentRepository.docId(kind, item.doc), item.shopId).isNotEmpty()
                            ledger || db.moneyEntryDao().forParent(MoneySource.PURCHASE, item.purchaseId).any { r ->
                                r.sourceId == item.doc &&
                                    r.sourceType in listOf(MoneySource.PURCHASE, MoneySource.PURCHASE_NOTE, MoneySource.PURCHASE_RETURN) &&
                                    (r.reason in listOf(MoneyReason.PURCHASE_REFUND, MoneyReason.PURCHASE_REFUND_DUE, MoneyReason.PURCHASE_PAID_DUE) ||
                                        (r.reason == MoneyReason.PURCHASE_PAID && r.sourceType == MoneySource.PURCHASE_NOTE))
                            }
                        } catch (_: Exception) { true }
                    }
                    if (started || kind == null) {
                        PendingPurchaseAdjustments.forget(activity, item.shopId, item.purchaseId, item.kind, item.doc)
                        next(i + 1)
                    } else {
                        PendingPurchaseAdjustments.countAttempt(activity, item)
                        Toast.makeText(activity, R.string.purchase_adjust_resume, Toast.LENGTH_LONG).show()
                        handlePurchase(activity, item.purchaseId, kind, item.amount, item.doc) { next(i + 1) }
                    }
                }
            }
            next(0)
        }
    }

    /**
     * Return booked straight against a supplier account (clear-stock / batch
     * return), with no single purchase invoice. Always a reduction; clamps to
     * the account balance and asks cash-vs-advance only on an overshoot.
     */
    fun handleAccountReturn(
        activity: AppCompatActivity,
        accountId: Int,
        amount: Double,
        documentLocalId: Int,
        onDone: () -> Unit
    ) = handleSource(
        activity, Source.SupplierAccount(accountId),
        Kind.PURCHASE_RETURN, amount, documentLocalId, onDone
    )

    private fun handleSource(
        activity: AppCompatActivity,
        source: Source,
        kind: Kind,
        amount: Double,
        documentLocalId: Int,
        finishFlow: () -> Unit
    ) {
        activity.lifecycleScope.launch {
            // A payment link is fixed at the amount it was made for. If goods come back before it is paid, the
            // customer can still pay the old, larger amount: say so once, at the very end of the flow.
            var oldLinkAmount = 0.0
            val afterAll: () -> Unit = {
                if (oldLinkAmount > 0.004 && !activity.isFinishing) {
                    AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.upi_link_old_title))
                        .setMessage(activity.getString(R.string.upi_link_old_msg, money(activity, oldLinkAmount)))
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss(); finishFlow() }
                        .show()
                } else finishFlow()
            }
            // Payment tracking (sales side, return / cancel only): whatever part of the document is NOT
            // on the credit account is money the customer paid, so it is refunded. The refund question is
            // asked after the credit question, together with any "settle in cash" leftover.
            val salesRefund = source is Source.Bill && !kind.raises
            // Purchase side: money coming back from the supplier (return / debit note / cancel), and the
            // extra to pay when a supplier note raises the bill of a purchase that was paid now.
            val supplierRefund = (source is Source.Purchase || source is Source.SupplierAccount) && !kind.raises
            val supplierPay = source is Source.Purchase && kind == Kind.PURCHASE_CREDIT_NOTE
            val moneyBack = salesRefund || supplierRefund
            if (salesRefund && kind == Kind.BILL_CANCEL) {
                // An unpaid UPI link on a cancelled bill will never be received.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.easy_billing.db.AppDatabase.getDatabase(activity).moneyEntryDao()
                        .cancelPending(MoneySource.BILL, (source as Source.Bill).billId)
                }
            }
            // Cancelling voids the whole document, so an extra charge / extra payment that was still waiting to be
            // collected / paid (a "due" note from a debit or credit note) is moot. Closed here, not left on a void bill.
            if (kind == Kind.BILL_CANCEL && source is Source.Bill) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.easy_billing.db.AppDatabase.getDatabase(activity).moneyEntryDao()
                        .closeDuesForParent(MoneySource.BILL, source.billId, listOf(MoneyReason.SALE_EXTRA_DUE))
                }
            }
            if (kind == Kind.PURCHASE_CANCEL && source is Source.Purchase) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.easy_billing.db.AppDatabase.getDatabase(activity).moneyEntryDao()
                        .closeDuesForParent(MoneySource.PURCHASE, source.purchaseId, listOf(MoneyReason.PURCHASE_PAID_DUE))
                }
            }
            // Read BEFORE the credit side is committed below.
            val creditShare = if (moneyBack)
                CreditAdjustmentRepository.creditShareOf(activity, source, amount) else 0.0
            // A return booked straight on a supplier account has no paid part, only a possible cash leftover.
            var paidPart = if (moneyBack && source !is Source.SupplierAccount) maxOf(0.0, amount - creditShare) else 0.0
            if (salesRefund && source is Source.Bill) {
                val db = com.example.easy_billing.db.AppDatabase.getDatabase(activity)
                val book = com.example.easy_billing.repository.MoneyBook(db.moneyEntryDao())
                if (kind == Kind.BILL_CANCEL) {
                    // A void bill gives back everything it still holds (including extra charges from debit
                    // notes), not just its original total.
                    paidPart = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        book.refundCeiling(MoneySource.BILL, source.billId, MoneyDirection.IN)
                    }
                } else if (kind == Kind.SALE_RETURN) {
                    // Goods came back before the customer paid by UPI: they owe less, so the pending UPI amount
                    // shrinks first. Only what is left of the paid part is money to hand back.
                    val shrunk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        book.shrinkPendingUpi(source.billId, paidPart)
                    }
                    paidPart = maxOf(0.0, paidPart - shrunk)
                    if (shrunk > 0.004) {
                        val billNo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try { db.billDao().getBillById(source.billId).billNumber.trim() } catch (_: Exception) { "" }
                        }
                        oldLinkAmount = UpiLinkAmounts.recall(activity, billNo) ?: 0.0
                    }
                }
            }
            android.util.Log.d("MoneyFlow", "adjust kind=$kind source=$source amount=$amount creditShare=$creditShare paidPart=$paidPart salesRefund=$salesRefund supplierRefund=$supplierRefund supplierPay=$supplierPay")
            val onDone: (cashExcess: Double) -> Unit = { cashExcess ->
                if (salesRefund) {
                    // A cash settlement can only come out of the credit part, never out of the paid part.
                    SalesRefundPrompt.ask(
                        activity, (source as Source.Bill).billId, kind, documentLocalId,
                        paidPart, minOf(cashExcess, creditShare), afterAll
                    )
                } else if (supplierRefund) {
                    PurchaseMoneyPrompt.askRefund(
                        activity, source, kind, documentLocalId,
                        paidPart, minOf(cashExcess, creditShare), afterAll
                    )
                } else afterAll()
            }
            // Nothing of this document is on credit any more (cash bill, or its credit part is already used
            // up by earlier returns): there is no credit question, only the refund of the paid money.
            if (moneyBack && creditShare <= 0.005) {
                onDone(0.0)
                return@launch
            }
            when (val plan = CreditAdjustmentRepository.planForSource(activity, source, kind, amount)) {

                // Cash bill/purchase, deleted account, or nothing to do.
                Plan.NotLinked ->
                    if (supplierPay) PurchaseMoneyPrompt.askPay(
                        activity, (source as Source.Purchase).purchaseId, documentLocalId, amount, afterAll
                    ) else onDone(0.0)

                // A charge: always adds to the balance, no question.
                is Plan.AddCharge -> {
                    val r = CreditAdjustmentRepository.commitSource(
                        activity, source, kind, plan.amount, documentLocalId
                    )
                    toast(activity, kind, r)
                    afterAll()
                }

                // Return fully absorbed by the dues: reduce silently.
                is Plan.Auto -> {
                    val r = CreditAdjustmentRepository.commitSource(
                        activity, source, kind, plan.reduce, documentLocalId
                    )
                    toast(activity, kind, r)
                    onDone(0.0)
                }

                // Return overshoots the dues: ask only about the leftover.
                is Plan.Choice -> askExcess(activity, source, kind, documentLocalId, plan, onDone)
            }
        }
    }

    private fun askExcess(
        activity: AppCompatActivity,
        source: Source,
        kind: Kind,
        documentLocalId: Int,
        plan: Plan.Choice,
        onDone: (cashExcess: Double) -> Unit
    ) {
        // "They" is the other party — the customer on a sale, the supplier on a
        // purchase. Direction of the leftover flips with the side.
        val party = plan.account.name
        val clearsLine =
            if (plan.reduce > 0.005)
                activity.getString(R.string.credit_adjustment_clears_dues, money(activity, plan.reduce), party) + "\n"
            else
                activity.getString(R.string.credit_adjustment_no_dues_left, party) + "\n"

        val msg = buildString {
            append(clearsLine)
            append(activity.getString(R.string.credit_adjustment_extra_left, money(activity, plan.excess)) + "\n\n")
            append(activity.getString(R.string.credit_adjustment_settle_or_advance, money(activity, plan.excess), party))
        }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.credit_adjustment_extra_after_clearing_title))
            .setPositiveButton(activity.getString(R.string.credit_adjustment_settle_now_btn)) { d, _ ->
                d.dismiss()
                // Supplier returns from the Inventory page: note the "cash" answer so a closed app can still ask for the refund.
                if (kind == Kind.PURCHASE_RETURN) CashExcessMemo.remember(activity, documentLocalId, plan.excess)
                // Only the debt-clearing part touches the account; the excess
                // is cash and is not recorded on the ledger.
                // The excess is cash handed back to the customer: it goes to the refund sheet.
                commitAndFinish(activity, source, kind, plan.reduce, documentLocalId) { onDone(plan.excess) }
            }
            .setNegativeButton(activity.getString(R.string.credit_adjustment_keep_as_advance_btn)) { d, _ ->
                d.dismiss()
                // The excess stays on the account, so the balance moves into
                // advance by that much.
                commitAndFinish(activity, source, kind, plan.reduce + plan.excess, documentLocalId) { onDone(0.0) }
            }
            // An answer is required: backing out used to skip the ledger entry altogether, leaving the supplier /
            // customer balance too high with nothing to show it was missed.
            .setCancelable(false)
            .show()
    }

    private fun commitAndFinish(
        activity: AppCompatActivity,
        source: Source,
        kind: Kind,
        billedReduction: Double,
        documentLocalId: Int,
        onDone: () -> Unit
    ) {
        activity.lifecycleScope.launch {
            val r = CreditAdjustmentRepository.commitSource(
                activity, source, kind, billedReduction, documentLocalId
            )
            toast(activity, kind, r)
            onDone()
        }
    }

    private fun toast(activity: AppCompatActivity, kind: Kind, r: PostResult) {
        val text = when (r) {
            is PostResult.Ok ->
                if (kind.raises) activity.getString(R.string.credit_toast_added, money(activity, r.appliedAmount))
                else activity.getString(R.string.credit_toast_reduced, money(activity, r.appliedAmount))
            PostResult.AlreadyPosted -> activity.getString(R.string.credit_toast_already)
            PostResult.Nothing -> activity.getString(R.string.credit_toast_nothing)
            PostResult.NoShop -> activity.getString(R.string.credit_toast_no_shop)
            PostResult.NotFound -> activity.getString(R.string.credit_toast_not_found)
        }
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
