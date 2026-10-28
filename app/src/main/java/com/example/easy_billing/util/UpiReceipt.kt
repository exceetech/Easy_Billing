package com.example.easy_billing.util

import android.content.Context
import androidx.room.withTransaction
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Bill
import com.example.easy_billing.repository.MoneyBook

/**
 * Remembers how much a UPI pay link / QR was made for, and handles the case where the customer pays that
 * (older, larger) amount after goods were returned.
 *
 * The server fixes a link's amount when the link is made, and this app cannot change it. When a return
 * lowers what the customer owes AFTER the link was sent, the customer can still pay the old amount. Then the
 * extra is real money in hand: it is recorded and a "refund still due" note is saved for it.
 */
object UpiLinkAmounts {
    private const val PREFS = "upi_link_amounts"

    fun remember(context: Context, billNumber: String, amount: Double) {
        if (billNumber.isBlank() || amount <= 0.0) return
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(billNumber, PaymentSplit.round2(amount).toString()).apply()
        } catch (_: Exception) { }
    }

    fun recall(context: Context, billNumber: String): Double? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(billNumber, null)?.toDoubleOrNull()
    } catch (_: Exception) { null }
}

object UpiReceipt {

    /** The pay link (or QR) was paid: the pending UPI money becomes real, and any overpayment is noted. */
    suspend fun onLinkPaid(context: Context, db: AppDatabase, bill: Bill) {
        val dao = db.moneyEntryDao()
        val flipped = dao.markReceived(MoneySource.BILL, bill.id, MoneyMode.UPI, appNow())
        if (flipped <= 0) return
        // A pure-UPI link made without an amount asked for the whole original bill.
        val asked = UpiLinkAmounts.recall(context, bill.billNumber)
            ?: (if (PaymentMethodText.isPureUpi(bill.paymentMethod)) bill.total else return)
        val got = dao.receivedUpiForBill(bill.id)
        val over = PaymentSplit.round2(asked - got)
        if (over <= 0.004) return
        recordOverpay(db, bill, over)
    }

    /** Extra UPI money in hand beyond what the bill now asks, written together with its "refund still due" note. */
    suspend fun recordOverpay(db: AppDatabase, bill: Bill, over: Double) {
        if (over <= 0.004) return
        val dao = db.moneyEntryDao()
        db.withTransaction {
            val book = MoneyBook(dao)
            val wrote = book.record(
                MoneyDirection.IN, MoneyMode.UPI, over, MoneyReason.SALE_EXTRA, MoneySource.BILL, bill.id,
                MoneySource.BILL, bill.id, refText = bill.billNumber.trim()
            )
            if (wrote) book.openDue(
                MoneyDirection.OUT, MoneyReason.SALE_REFUND_DUE, over, MoneySource.BILL, bill.id,
                MoneySource.BILL, bill.id, refText = bill.billNumber.trim()
            )
        }
    }

    /**
     * For the manual "Mark as paid": how far the remembered pay-link amount is above what the customer owes now
     * (0 when no link amount is remembered, or nothing is above). Read BEFORE the pending UPI is marked received.
     */
    suspend fun linkAboveOwed(context: Context, db: AppDatabase, bill: Bill): Pair<Double, Double> {
        val asked = UpiLinkAmounts.recall(context, bill.billNumber) ?: return 0.0 to 0.0
        val dao = db.moneyEntryDao()
        val owed = dao.pendingUpiRows(bill.id).sumOf { it.amount }
        if (owed <= 0.004) return 0.0 to 0.0
        val over = PaymentSplit.round2(asked - owed - dao.receivedUpiForBill(bill.id))
        return if (over > 0.004) owed to asked else 0.0 to 0.0
    }
}
