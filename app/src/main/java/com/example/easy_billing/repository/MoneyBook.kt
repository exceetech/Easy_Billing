package com.example.easy_billing.repository

import com.example.easy_billing.db.MoneyEntry
import com.example.easy_billing.db.MoneyEntryDao
import com.example.easy_billing.util.MoneyDirection
import com.example.easy_billing.util.MoneyMode
import com.example.easy_billing.util.MoneyReason
import com.example.easy_billing.util.MoneySource
import com.example.easy_billing.util.MoneyStatus
import com.example.easy_billing.util.PayLine
import com.example.easy_billing.util.PaymentSplit
import com.example.easy_billing.util.appNow
import java.util.UUID

/**
 * Single entry point for writing the Money Book.
 *
 * Call it from INSIDE the same `db.withTransaction { }` that saves the business event, so the
 * event and its money rows are saved together or not at all. Bad input throws
 * [IllegalArgumentException] on purpose: inside the transaction that rolls the whole event back
 * instead of saving a bill whose money was silently lost.
 */
class MoneyBook(private val dao: MoneyEntryDao) {

    companion object { const val REVERSAL_PREFIX = "Reversal: " }

    /**
     * Writes one row. Returns false (and writes nothing) when the same document already wrote
     * this direction / mode / reason, e.g. after a double tap.
     */
    suspend fun record(
        direction: String,
        mode: String,
        amount: Double,
        reason: String,
        sourceType: String,
        sourceId: Int,
        parentType: String = sourceType,
        parentId: Int = sourceId,
        partyName: String = "",
        refText: String = "",
        status: String = MoneyStatus.RECEIVED,
        at: Long = appNow()
    ): Boolean {
        require(MoneyDirection.isValid(direction)) { "Bad direction: $direction" }
        require(MoneyMode.isValid(mode)) { "Bad money mode: $mode" }
        require(MoneyReason.isValid(reason)) { "Bad reason: $reason" }
        require(MoneySource.isValid(sourceType) && MoneySource.isValid(parentType)) { "Bad source: $sourceType/$parentType" }
        require(MoneyStatus.isValid(status)) { "Bad status: $status" }
        val rounded = PaymentSplit.round2(amount)
        require(!amount.isNaN() && rounded > 0.0) { "Money amount must be above zero: $amount" }

        val id = dao.insert(
            MoneyEntry(
                uid = UUID.randomUUID().toString().replace("-", ""),
                direction = direction, mode = mode, amount = rounded, reason = reason,
                sourceType = sourceType, sourceId = sourceId,
                parentType = parentType, parentId = parentId,
                partyName = partyName.take(120), refText = refText.take(60),
                status = status, createdAt = at, isSynced = false
            )
        )
        return id != -1L
    }

    /**
     * Saves a "money still due" note (PENDING, mode CASH only because a mode is required; it is not money
     * and is never counted). [dueReason] is one of [MoneyReason.DUE_REASONS]. Returns false when the same
     * document already has this note. Call it BEFORE showing the sheet, so closing the sheet or killing the
     * app can no longer lose the refund.
     */
    suspend fun openDue(
        direction: String,
        dueReason: String,
        amount: Double,
        sourceType: String,
        sourceId: Int,
        parentType: String,
        parentId: Int,
        partyName: String = "",
        refText: String = ""
    ): Boolean {
        require(MoneyReason.isDue(dueReason)) { "Not a due reason: $dueReason" }
        if (PaymentSplit.round2(amount) <= 0.0) return false
        return record(
            direction, MoneyMode.CASH, amount, dueReason, sourceType, sourceId,
            parentType, parentId, partyName, refText, MoneyStatus.PENDING
        )
    }

    /** Closes the due notes of one document (after it was settled, or when nothing is left to settle). */
    suspend fun closeDues(sourceType: String, sourceId: Int, vararg dueReasons: String) {
        dao.closeDues(sourceType, sourceId, dueReasons.toList())
    }

    /**
     * Writes one row per line. A CREDIT line is not money and is skipped. Lines of the same mode are
     * merged first. Returns how many rows were newly written.
     */
    suspend fun recordLines(
        direction: String,
        lines: List<PayLine>,
        reason: String,
        sourceType: String,
        sourceId: Int,
        parentType: String = sourceType,
        parentId: Int = sourceId,
        partyName: String = "",
        refText: String = "",
        status: String = MoneyStatus.RECEIVED,
        at: Long = appNow()
    ): Int {
        var written = 0
        for (l in PaymentSplit.merge(lines.filter { it.mode != MoneyMode.CREDIT })) {
            if (record(direction, l.mode, l.amount, reason, sourceType, sourceId,
                    parentType, parentId, partyName, refText, status, at)) written++
        }
        return written
    }

    /**
     * How much may still be paid back for a bill / purchase.
     * [originalDirection] is the direction the money first moved: IN for a sale, OUT for a purchase.
     */
    suspend fun refundCeiling(parentType: String, parentId: Int, originalDirection: String): Double {
        val (movedReasons, returnedReasons) = reasonsFor(originalDirection)
        val moved = dao.sumForParentReasons(parentType, parentId, originalDirection, movedReasons)
        val returned = dao.sumForParentReasons(parentType, parentId, MoneyDirection.opposite(originalDirection), returnedReasons)
        return PaymentSplit.refundCeiling(moved, returned)
    }

    /**
     * How the money of a bill / purchase was paid, per mode (moved minus already paid back, never below 0).
     * Used to split a refund "the same way it came in".
     */
    suspend fun refundBasis(parentType: String, parentId: Int, originalDirection: String): List<PayLine> {
        val (movedReasons, returnedReasons) = reasonsFor(originalDirection)
        val moved = dao.sumByModeReasons(parentType, parentId, originalDirection, movedReasons).associate { it.mode to it.total }
        val returned = dao.sumByModeReasons(parentType, parentId, MoneyDirection.opposite(originalDirection), returnedReasons)
            .associate { it.mode to it.total }
        return MoneyMode.ALL.mapNotNull { m ->
            val net = PaymentSplit.toPaise(moved[m] ?: 0.0) - PaymentSplit.toPaise(returned[m] ?: 0.0)
            if (net > 0L) PayLine(m, PaymentSplit.fromPaise(net)) else null
        }
    }

    /** (reasons that count as the original money, reasons that count as paying it back) */
    private fun reasonsFor(originalDirection: String): Pair<List<String>, List<String>> =
        if (originalDirection == MoneyDirection.IN)
            listOf(MoneyReason.SALE, MoneyReason.SALE_EXTRA) to listOf(MoneyReason.SALE_REFUND)
        else
            listOf(MoneyReason.PURCHASE_PAID) to listOf(MoneyReason.PURCHASE_REFUND)

    // ── Manual entries (Phase 7) ──────────────────────────────────────────────────────────────────

    /** Other income (IN) or other expense (OUT) in one mode. Returns false when nothing was written. */
    suspend fun addManual(direction: String, mode: String, amount: Double, note: String, party: String = ""): Boolean {
        val sid = dao.nextManualSourceId()
        return record(direction, mode, amount, MoneyReason.MANUAL, MoneySource.MANUAL, sid,
            partyName = party, refText = note.trim())
    }

    /** Moves money between pocket and bank: one OUT leg and one IN leg that share one source id. */
    suspend fun addTransfer(fromMode: String, toMode: String, amount: Double, note: String): Boolean {
        require(fromMode != toMode) { "Transfer needs two different modes" }
        val sid = dao.nextManualSourceId()
        val a = record(MoneyDirection.OUT, fromMode, amount, MoneyReason.TRANSFER, MoneySource.MANUAL, sid, refText = note.trim())
        val b = record(MoneyDirection.IN, toMode, amount, MoneyReason.TRANSFER, MoneySource.MANUAL, sid, refText = note.trim())
        return a && b
    }

    /**
     * Undoes a manual / opening / transfer entry by writing the opposite row(s); nothing is ever edited or deleted.
     * The same entry cannot be reversed twice (the unique index ignores the second try), and a reversal cannot be
     * reversed. Returns how many rows were written.
     */
    suspend fun reverseManual(sourceId: Int): Int {
        var written = 0
        for (e in dao.forManual(sourceId)) {
            if (e.status != MoneyStatus.RECEIVED || e.refText.startsWith(REVERSAL_PREFIX)) continue
            if (e.reason != MoneyReason.MANUAL && e.reason != MoneyReason.OPENING && e.reason != MoneyReason.TRANSFER) continue
            // An entry whose opposite leg already exists is itself a reversal leg (or already reversed).
            val label = e.refText.ifBlank { e.reason }
            if (record(MoneyDirection.opposite(e.direction), e.mode, e.amount, e.reason, MoneySource.MANUAL, sourceId,
                    partyName = e.partyName, refText = (REVERSAL_PREFIX + label).take(60))) written++
        }
        return written
    }

    /**
     * Goods were returned from a bill whose UPI money has not arrived yet: the customer now owes less, so the
     * pending UPI amount shrinks by up to [cut] (a row that reaches zero is closed as CANCELLED). Pending is not
     * money yet, so changing its amount does not break the append-only rule for real money.
     * Returns how much was taken off (never more than the pending total).
     */
    suspend fun shrinkPendingUpi(billId: Int, cut: Double): Double {
        var left = PaymentSplit.round2(cut)
        if (left <= 0.0) return 0.0
        var done = 0.0
        for (row in dao.pendingUpiRows(billId)) {
            if (left <= 0.0) break
            val take = minOf(left, row.amount)
            val remain = PaymentSplit.round2(row.amount - take)
            val changed = if (remain <= 0.004) dao.reducePending(row.id, row.amount, MoneyStatus.CANCELLED)
                          else dao.reducePending(row.id, remain, MoneyStatus.PENDING)
            if (changed > 0) { left = PaymentSplit.round2(left - take); done = PaymentSplit.round2(done + take) }
        }
        return done
    }

    /** A UPI payment link was paid: its PENDING row becomes RECEIVED. */
    suspend fun markReceived(sourceType: String, sourceId: Int, mode: String = MoneyMode.UPI): Boolean =
        dao.markReceived(sourceType, sourceId, mode, appNow()) > 0
}
