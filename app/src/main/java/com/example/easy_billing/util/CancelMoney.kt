package com.example.easy_billing.util

import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.repository.MoneyBook

/**
 * The money side of a cancelled bill, kept safe on its own:
 *  - a UPI payment that never arrived is closed (it will never be received),
 *  - an extra charge that was still waiting to be collected is closed (the bill is void),
 *  - every rupee that WAS received and not yet handed back gets a "refund still due" note.
 *
 * It is called inside the cancel transaction, so the bill can never be void without its refund note, and it
 * is safe to call again later (when a cancelled bill is opened or listed) to repair a bill that was cancelled
 * before this existed or whose app was closed half-way. Calling it twice changes nothing.
 */
object CancelMoney {

    /** Returns the refund that is (still) due for [billId] after the notes are in place; 0 when none. */
    suspend fun ensureRefundNote(db: AppDatabase, billId: Int): Double {
        val dao = db.moneyEntryDao()
        val bill = db.billDao().getBillById(billId)
        if (!bill.isCancelled) return 0.0

        dao.cancelPending(MoneySource.BILL, billId)
        dao.closeDuesForParent(MoneySource.BILL, billId, listOf(MoneyReason.SALE_EXTRA_DUE))

        val book = MoneyBook(dao)
        // Everything received for this bill (sale + extra charges) minus what was already handed back.
        val ceiling = book.refundCeiling(MoneySource.BILL, billId, MoneyDirection.IN)
        val open = dao.openDues(MoneySource.BILL, billId)
            .filter { it.direction == MoneyDirection.OUT && it.reason == MoneyReason.SALE_REFUND_DUE }
        val mine = open.filter { it.sourceType == MoneySource.BILL && it.sourceId == billId }
        val others = open.filter { !(it.sourceType == MoneySource.BILL && it.sourceId == billId) }.sumOf { it.amount }

        // Earlier returns may already have their own refund notes; this note covers only the rest.
        val want = PaymentSplit.round2(ceiling - others)
        val have = PaymentSplit.round2(mine.sumOf { it.amount })
        if (want <= 0.004) {
            if (mine.isNotEmpty()) book.closeDues(MoneySource.BILL, billId, MoneyReason.SALE_REFUND_DUE)
            return if (others > 0.0) minOf(others, ceiling) else 0.0
        }
        if (Math.abs(have - want) > 0.004) {
            if (mine.isNotEmpty()) book.closeDues(MoneySource.BILL, billId, MoneyReason.SALE_REFUND_DUE)
            book.openDue(
                MoneyDirection.OUT, MoneyReason.SALE_REFUND_DUE, want, MoneySource.BILL, billId,
                MoneySource.BILL, billId, refText = bill.billNumber.trim()
            )
        }
        return ceiling
    }
}
