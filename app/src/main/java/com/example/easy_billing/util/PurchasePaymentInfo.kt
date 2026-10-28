package com.example.easy_billing.util

import android.content.Context
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.MoneyEntry
import com.example.easy_billing.db.Purchase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the Money Book says about ONE purchase, for display only (Purchase history rows / tiles and the
 * Purchase details "Payment" card). Reads rows that already exist; writes nothing.
 *
 *  - [paidByMode]  money the shop paid out for this purchase, per mode (empty for a credit purchase, and for an
 *                  old purchase from before payments were tracked: [tracked] is then false)
 *  - [refunded]    supplier refunds actually received back
 *  - [refundDue] / [payDue]  "still to record" notes (a refund to receive / an extra payment to make)
 */
object PurchasePaymentInfo {

    data class Info(
        val paidByMode: List<PayLine> = emptyList(),
        val refunded: Double = 0.0,
        val refundDue: Double = 0.0,
        val payDue: Double = 0.0
    ) {
        val tracked: Boolean get() = paidByMode.isNotEmpty()
        val paidTotal: Double get() = paidByMode.sumOf { it.amount }
    }

    fun build(rows: List<MoneyEntry>): Info {
        val live = rows.filter { it.status != MoneyStatus.CANCELLED }
        val paid = PaymentSplit.merge(
            live.filter { it.direction == MoneyDirection.OUT && it.reason == MoneyReason.PURCHASE_PAID && it.status == MoneyStatus.RECEIVED }
                .map { PayLine(it.mode, it.amount) }
        )
        val refunded = live.filter {
            it.direction == MoneyDirection.IN && it.reason == MoneyReason.PURCHASE_REFUND && it.status == MoneyStatus.RECEIVED
        }.sumOf { it.amount }
        val open = rows.filter { it.status == MoneyStatus.PENDING }
        return Info(
            paidByMode = paid,
            refunded = PaymentSplit.round2(refunded),
            refundDue = PaymentSplit.round2(open.filter { it.reason == MoneyReason.PURCHASE_REFUND_DUE }.sumOf { it.amount }),
            payDue = PaymentSplit.round2(open.filter { it.reason == MoneyReason.PURCHASE_PAID_DUE }.sumOf { it.amount })
        )
    }

    suspend fun load(context: Context, purchases: List<Purchase>): Map<Int, Info> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).moneyEntryDao()
        purchases.associate { p ->
            p.id to try { build(dao.forParent(MoneySource.PURCHASE, p.id)) } catch (_: Exception) { Info() }
        }
    }

    suspend fun loadOne(context: Context, purchaseId: Int): Info = withContext(Dispatchers.IO) {
        try { build(AppDatabase.getDatabase(context).moneyEntryDao().forParent(MoneySource.PURCHASE, purchaseId)) }
        catch (_: Exception) { Info() }
    }

    /** "Cash", "UPI" ... for one mode, "Split" for several, null when nothing is tracked. */
    fun modeText(context: Context, info: Info): String? = when {
        info.paidByMode.isEmpty() -> null
        info.paidByMode.size == 1 -> BillPaymentText.modeLabel(context, info.paidByMode[0].mode)
        else -> context.getString(R.string.purchase_history_status_split)
    }

    /** Short suffix for a list row: " · Refund due" / " · Payment due" (empty when nothing is pending). */
    fun dueSuffix(context: Context, info: Info?): String = when {
        info == null -> ""
        info.refundDue > 0.004 -> " · " + context.getString(R.string.purchase_history_refund_due)
        info.payDue > 0.004 -> " · " + context.getString(R.string.purchase_history_pay_due)
        else -> ""
    }
}
