package com.example.easy_billing.util

import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.CreditAccount

/**
 * How much of a credit bill is still sitting as debt. One calculation shared by the
 * "Still owed" banner on Bill details and the "On credit" tile on Bill history, so the two
 * screens can never disagree.
 *
 * A bill's own credit = the credit sale + extra charges - returns - cancel (its own ledger rows).
 * Payments are saved on the customer account, not on a bill, so the figure is capped by the
 * customer's current balance: a customer who has paid up no longer owes on any bill.
 */
object BillCredit {

    data class Owed(val amount: Double, val account: CreditAccount?)

    private fun isCreditBill(b: Bill) =
        !b.isCancelled && PaymentMethodText.hasCredit(b.paymentMethod)

    /** The bill's own credit from its ledger rows, never below zero. */
    suspend fun netCredit(db: AppDatabase, bill: Bill, shopId: Int): Double {
        val rows = db.creditTransactionDao().getByBill(bill.id, shopId)
        val net = if (rows.isEmpty()) {
            // No ledger rows (old bill): a whole-credit bill owes its total, a part-credit one is unknown.
            if (PaymentMethodText.isSplit(bill.paymentMethod)) 0.0 else bill.total
        } else rows.sumOf {
            when (it.type) {
                "ADD", "DEBIT_NOTE" -> it.amount
                "SALE_RETURN", "BILL_CANCEL" -> -it.amount
                else -> 0.0
            }
        }
        return if (net < 0.0) 0.0 else net
    }

    /** Null when the bill is cancelled or has no credit part. */
    suspend fun owedForBill(db: AppDatabase, bill: Bill, shopId: Int): Owed? {
        if (!isCreditBill(bill)) return null
        val net = netCredit(db, bill, shopId)
        val acc = bill.creditAccountId?.let { db.creditAccountDao().getById(it, shopId) }
        val amount = if (acc != null) minOf(net, maxOf(acc.dueAmount, 0.0)) else net
        return Owed(amount, acc)
    }

    /**
     * Total still owed across several bills. Bills of one customer are added first and then
     * capped by that customer's balance once, so the balance is never counted twice.
     */
    suspend fun owedForBills(db: AppDatabase, bills: List<Bill>, shopId: Int): Double {
        var total = 0.0
        val perAccount = HashMap<Int, Double>()
        for (b in bills) {
            if (!isCreditBill(b)) continue
            val net = netCredit(db, b, shopId)
            val accId = b.creditAccountId
            if (accId == null) total += net else perAccount[accId] = (perAccount[accId] ?: 0.0) + net
        }
        for ((accId, net) in perAccount) {
            val acc = db.creditAccountDao().getById(accId, shopId)
            total += if (acc != null) minOf(net, maxOf(acc.dueAmount, 0.0)) else net
        }
        return total
    }
}
