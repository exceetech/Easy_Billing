package com.example.easy_billing.util

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.MoneyEntry
import com.example.easy_billing.ui.MoneyViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One line of the full statement. Balances are the cash / bank balance AFTER this line. */
data class StmtRow(
    val no: Int,
    val at: Long,
    val type: String,
    val ref: String,
    val party: String,
    val mode: String,
    val moneyIn: Double,
    val moneyOut: Double,
    val cashBal: Double,
    val bankBal: Double,
    val waiting: Boolean,
    val opening: Boolean,
    /** Source id of a manual / opening / transfer entry that can still be reversed (screen only). */
    val reverseSid: Int? = null
) { val totalBal: Double get() = PaymentSplit.round2(cashBal + bankBal) }

/** Money in / out for one mode. */
data class StmtModeTotal(val mode: String, val moneyIn: Double, val moneyOut: Double)

/** Something not settled yet. [kind]: 0 = money waiting to arrive (UPI), 1 = money to give, 2 = money to receive. */
data class StmtSettleLine(val at: Long, val type: String, val ref: String, val party: String, val amount: Double, val kind: Int)

/**
 * Everything the full statement (Excel, PDF, print) shows, worked out once from the Money Book so all three
 * agree with each other, with the Money card and with the in-app statement.
 *
 * Only RECEIVED rows move a balance. Waiting (PENDING) rows are listed but never counted. Cancelled rows
 * and "money still due" notes are not money that moved, so they stay out of the table (the due notes are
 * listed separately under "still to settle").
 */
class MoneyStatementData(
    val periodText: String,
    val createdAt: Long,
    val opening: MoneyTotals,
    /** Money in / out of the period, leaving out the opening balance and cash-bank transfers (same as the Money card). */
    val earnings: MoneyTotals,
    /** Opening balance entered inside the period. */
    val openingAdded: MoneyTotals,
    val closing: MoneyTotals,
    val rows: List<StmtRow>,
    /** Net (in minus out) per type, e.g. Sale, Sale refund. */
    val byType: List<Pair<String, Double>>,
    val byMode: List<StmtModeTotal>,
    val settle: List<StmtSettleLine>
) {
    val waitingTotal: Double get() = PaymentSplit.round2(settle.filter { it.kind == 0 }.sumOf { it.amount })
    val toGiveTotal: Double get() = PaymentSplit.round2(settle.filter { it.kind == 1 }.sumOf { it.amount })
    val toReceiveTotal: Double get() = PaymentSplit.round2(settle.filter { it.kind == 2 }.sumOf { it.amount })

    companion object {
        private val SKIP = setOf(MoneyReason.OPENING, MoneyReason.TRANSFER)

        private fun totalsOf(entries: List<MoneyEntry>, skip: Set<String>, only: Set<String>? = null): MoneyTotals {
            val sums = HashMap<Pair<String, String>, Long>()
            for (e in entries) {
                if (e.status != MoneyStatus.RECEIVED) continue
                if (e.reason in skip) continue
                if (only != null && e.reason !in only) continue
                val k = e.mode to e.direction
                sums[k] = (sums[k] ?: 0L) + PaymentSplit.toPaise(e.amount)
            }
            return MoneyReport.totals(sums.map { ModeDirTotal(it.key.first, it.key.second, PaymentSplit.fromPaise(it.value)) })
        }

        suspend fun load(context: Context, startIso: String, endIso: String): MoneyStatementData =
            withContext(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(context).moneyEntryDao()
                val (from, to) = MoneyReport.dayBounds(startIso, endIso)
                val opening = MoneyReport.totals(dao.sumsBetween(0L, from, emptyList()))
                val entries = dao.between(from, to).filter {
                    it.status != MoneyStatus.CANCELLED && !MoneyReason.isDue(it.reason)
                }

                // Running balance in whole paise so it never drifts.
                var cashP = PaymentSplit.toPaise(opening.cashNet)
                var bankP = PaymentSplit.toPaise(opening.bankNet)
                val rows = ArrayList<StmtRow>(entries.size)
                for ((i, e) in entries.withIndex()) {
                    val waiting = e.status != MoneyStatus.RECEIVED
                    val p = PaymentSplit.toPaise(e.amount)
                    val signed = if (e.direction == MoneyDirection.IN) p else -p
                    if (!waiting) { if (e.mode == MoneyMode.CASH) cashP += signed else bankP += signed }
                    val manual = e.sourceType == MoneySource.MANUAL
                    val type = if (manual && e.refText.isNotBlank()) e.refText else MoneyViews.reasonLabel(context, e.reason)
                    rows += StmtRow(
                        no = i + 1, at = e.createdAt, type = type,
                        ref = if (manual) "" else e.refText, party = e.partyName,
                        mode = PaymentLinesSheet.modeLabel(context, e.mode),
                        moneyIn = if (e.direction == MoneyDirection.IN) e.amount else 0.0,
                        moneyOut = if (e.direction == MoneyDirection.OUT) e.amount else 0.0,
                        cashBal = PaymentSplit.fromPaise(cashP), bankBal = PaymentSplit.fromPaise(bankP),
                        waiting = waiting, opening = e.reason == MoneyReason.OPENING,
                        reverseSid = if (manual && !waiting && !e.refText.startsWith(com.example.easy_billing.repository.MoneyBook.REVERSAL_PREFIX)) e.sourceId else null
                    )
                }

                val earnings = totalsOf(entries, SKIP)
                val openingAdded = totalsOf(entries, emptySet(), setOf(MoneyReason.OPENING))
                val closing = MoneyReport.closing(opening, totalsOf(entries, emptySet()))

                // By type: net per type (money in minus money out), in order of first appearance.
                val typeNet = LinkedHashMap<String, Long>()
                for (e in entries) {
                    if (e.status != MoneyStatus.RECEIVED || e.reason in SKIP) continue
                    val label = MoneyViews.reasonLabel(context, e.reason)
                    val p = PaymentSplit.toPaise(e.amount)
                    typeNet[label] = (typeNet[label] ?: 0L) + (if (e.direction == MoneyDirection.IN) p else -p)
                }
                val byType = typeNet.map { it.key to PaymentSplit.fromPaise(it.value) }

                val byMode = MoneyMode.ALL.mapNotNull { m ->
                    var inP = 0L; var outP = 0L
                    for (e in entries) {
                        if (e.status != MoneyStatus.RECEIVED || e.reason in SKIP || e.mode != m) continue
                        val p = PaymentSplit.toPaise(e.amount)
                        if (e.direction == MoneyDirection.IN) inP += p else outP += p
                    }
                    if (inP == 0L && outP == 0L) null
                    else StmtModeTotal(PaymentLinesSheet.modeLabel(context, m), PaymentSplit.fromPaise(inP), PaymentSplit.fromPaise(outP))
                }

                // Still to settle: any date, never counted in a balance.
                val settle = ArrayList<StmtSettleLine>()
                for (e in dao.allPendingMoney()) {
                    settle += StmtSettleLine(e.createdAt, MoneyViews.reasonLabel(context, e.reason) +
                        " · " + PaymentLinesSheet.modeLabel(context, e.mode), e.refText, e.partyName, e.amount, 0)
                }
                for (e in dao.allOpenDues()) {
                    settle += StmtSettleLine(e.createdAt, MoneyViews.reasonLabel(context, e.reason), e.refText, e.partyName,
                        e.amount, if (e.direction == MoneyDirection.OUT) 1 else 2)
                }

                val f = AppTime.isoDate(); val o = AppTime.formatter("dd MMM yyyy")
                val a = o.format(f.parse(startIso)!!); val b = o.format(f.parse(endIso)!!)
                MoneyStatementData(
                    periodText = if (startIso == endIso) a else "$a – $b",
                    createdAt = appNow(), opening = opening, earnings = earnings, openingAdded = openingAdded,
                    closing = closing, rows = rows, byType = byType, byMode = byMode, settle = settle
                )
            }
    }
}
