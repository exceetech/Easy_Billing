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
    ) = handleSource(activity, Source.Purchase(purchaseId), kind, amount, documentLocalId, onDone)

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
        afterAll: () -> Unit
    ) {
        activity.lifecycleScope.launch {
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
            // Read BEFORE the credit side is committed below.
            val creditShare = if (moneyBack)
                CreditAdjustmentRepository.creditShareOf(activity, source, amount) else 0.0
            // A return booked straight on a supplier account has no paid part, only a possible cash leftover.
            val paidPart = if (moneyBack && source !is Source.SupplierAccount) maxOf(0.0, amount - creditShare) else 0.0
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
                "${money(activity, plan.reduce)} clears $party's remaining dues.\n"
            else
                "$party has no dues left to clear.\n"

        val msg = buildString {
            append(clearsLine)
            append("Extra ${money(activity, plan.excess)} is left over.\n\n")
            append("Settle the ${money(activity, plan.excess)} in cash, or keep it as an advance " +
                "on $party's account?")
        }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.credit_adjustment_extra_after_clearing_title))
            .setPositiveButton(activity.getString(R.string.credit_adjustment_settle_now_btn)) { d, _ ->
                d.dismiss()
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
            .setOnCancelListener { onDone(0.0) }
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
                if (kind.raises) "Added ${money(activity, r.appliedAmount)} to balance"
                else "Reduced balance by ${money(activity, r.appliedAmount)}"
            PostResult.AlreadyPosted -> "Already applied to the account"
            PostResult.Nothing -> "Recorded — no change to balance"
            PostResult.NoShop -> "No shop selected. Sign in again."
            PostResult.NotFound -> "Couldn't find the account"
        }
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
