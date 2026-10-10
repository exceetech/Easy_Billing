package com.example.easy_billing.util

import android.content.Context
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Bill

/**
 * one line per mode ("Cash ₹300", "UPI ₹200", "Credit ₹500", separated by new lines) for a split bill, built from this phone's Money Book
 * (and the credit ledger for the credit part). Returns null for a single-method bill, or when the
 * amounts are not on this phone, so callers just fall back to the method name.
 */
object BillPaymentText {

    fun modeLabel(context: Context, mode: String): String = when (mode) {
        MoneyMode.CASH -> context.getString(R.string.cash)
        MoneyMode.UPI -> context.getString(R.string.upi)
        MoneyMode.CARD -> context.getString(R.string.card)
        MoneyMode.BANK -> context.getString(R.string.payment_mode_bank)
        MoneyMode.CREDIT -> context.getString(R.string.invoice_payment_credit)
        else -> mode
    }

    suspend fun breakdown(context: Context, bill: Bill): String? {
        if (!PaymentMethodText.isSplit(bill.paymentMethod)) return null
        val db = AppDatabase.getDatabase(context)
        val money = db.moneyEntryDao().forParent(MoneySource.BILL, bill.id)
            .filter { it.direction == MoneyDirection.IN && it.reason == MoneyReason.SALE }
            .map { PayLine(it.mode, it.amount) }
        val shop = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getInt("SHOP_ID", -1)
        val credit = if (shop > 0 && PaymentMethodText.hasCredit(bill.paymentMethod))
            db.creditTransactionDao().getByBill(bill.id, shop).filter { it.type == "ADD" }.sumOf { it.amount }
        else 0.0
        val lines = PaymentSplit.merge(money + if (credit > 0.0) listOf(PayLine(MoneyMode.CREDIT, credit)) else emptyList())
        if (lines.size < 2) return null
        return lines.joinToString("\n") { "${modeLabel(context, it.mode)} ${CurrencyHelper.format(context, it.amount)}" }
    }
}
