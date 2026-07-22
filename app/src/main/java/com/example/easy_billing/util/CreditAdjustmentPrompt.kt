package com.example.easy_billing.util

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

    private fun money(v: Double): String =
        if (v % 1.0 == 0.0) "₹${v.toLong()}" else "₹${"%.2f".format(v)}"

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
        onDone: () -> Unit
    ) {
        activity.lifecycleScope.launch {
            when (val plan = CreditAdjustmentRepository.planForSource(activity, source, kind, amount)) {

                // Cash bill/purchase, deleted account, or nothing to do.
                Plan.NotLinked -> onDone()

                // A charge: always adds to the balance, no question.
                is Plan.AddCharge -> {
                    val r = CreditAdjustmentRepository.commitSource(
                        activity, source, kind, plan.amount, documentLocalId
                    )
                    toast(activity, kind, r)
                    onDone()
                }

                // Return fully absorbed by the dues: reduce silently.
                is Plan.Auto -> {
                    val r = CreditAdjustmentRepository.commitSource(
                        activity, source, kind, plan.reduce, documentLocalId
                    )
                    toast(activity, kind, r)
                    onDone()
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
        onDone: () -> Unit
    ) {
        // "They" is the other party — the customer on a sale, the supplier on a
        // purchase. Direction of the leftover flips with the side.
        val party = plan.account.name
        val clearsLine =
            if (plan.reduce > 0.005)
                "${money(plan.reduce)} clears $party's remaining dues.\n"
            else
                "$party has no dues left to clear.\n"

        val msg = buildString {
            append(clearsLine)
            append("Extra ${money(plan.excess)} is left over.\n\n")
            append("Settle the ${money(plan.excess)} in cash, or keep it as an advance " +
                "on $party's account?")
        }

        AlertDialog.Builder(activity)
            .setTitle("Extra after clearing dues")
            .setPositiveButton("Cash — settle now") { d, _ ->
                d.dismiss()
                // Only the debt-clearing part touches the account; the excess
                // is cash and is not recorded on the ledger.
                commitAndFinish(activity, source, kind, plan.reduce, documentLocalId, onDone)
            }
            .setNegativeButton("Keep as advance") { d, _ ->
                d.dismiss()
                // The excess stays on the account, so the balance moves into
                // advance by that much.
                commitAndFinish(activity, source, kind, plan.reduce + plan.excess, documentLocalId, onDone)
            }
            .setOnCancelListener { onDone() }
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
                if (kind.raises) "Added ${money(r.appliedAmount)} to balance"
                else "Reduced balance by ${money(r.appliedAmount)}"
            PostResult.AlreadyPosted -> "Already applied to the account"
            PostResult.Nothing -> "Recorded — no change to balance"
            PostResult.NoShop -> "No shop selected. Sign in again."
            PostResult.NotFound -> "Couldn't find the account"
        }
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
