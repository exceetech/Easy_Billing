package com.example.easy_billing.util

import java.util.Calendar

/** One group from the Money Book: total of RECEIVED money for a mode and a direction. */
data class ModeDirTotal(val mode: String, val direction: String, val total: Double)

/**
 * Money in and out, split into "in pocket" (CASH) and "in bank" (UPI, card, bank transfer).
 * Kept in whole paise so adding and subtracting never drifts.
 */
data class MoneyTotals(
    val inCashP: Long = 0L,
    val outCashP: Long = 0L,
    val inBankP: Long = 0L,
    val outBankP: Long = 0L
) {
    val cashNetP get() = inCashP - outCashP
    val bankNetP get() = inBankP - outBankP
    val inTotal get() = PaymentSplit.fromPaise(inCashP + inBankP)
    val outTotal get() = PaymentSplit.fromPaise(outCashP + outBankP)
    val inCash get() = PaymentSplit.fromPaise(inCashP)
    val inBank get() = PaymentSplit.fromPaise(inBankP)
    val outCash get() = PaymentSplit.fromPaise(outCashP)
    val outBank get() = PaymentSplit.fromPaise(outBankP)
    val cashNet get() = PaymentSplit.fromPaise(cashNetP)
    val bankNet get() = PaymentSplit.fromPaise(bankNetP)
    val net get() = PaymentSplit.fromPaise(cashNetP + bankNetP)

    operator fun plus(o: MoneyTotals) = MoneyTotals(
        inCashP + o.inCashP, outCashP + o.outCashP, inBankP + o.inBankP, outBankP + o.outBankP
    )
}

/** Pure money-report arithmetic (no Android), so it can be tested on the JVM. */
object MoneyReport {

    /** Adds up grouped rows. Only CASH is pocket money; every other mode is in the bank. */
    fun totals(rows: List<ModeDirTotal>): MoneyTotals {
        var inC = 0L; var outC = 0L; var inB = 0L; var outB = 0L
        for (r in rows) {
            val p = PaymentSplit.toPaise(r.total)
            val cash = r.mode == MoneyMode.CASH
            if (r.direction == MoneyDirection.IN) { if (cash) inC += p else inB += p }
            else if (r.direction == MoneyDirection.OUT) { if (cash) outC += p else outB += p }
        }
        return MoneyTotals(inC, outC, inB, outB)
    }

    /** Balance at the end of a period = balance before it + that period's net. */
    fun closing(opening: MoneyTotals, within: MoneyTotals): MoneyTotals = opening + within

    /** [start, next day after end) in milliseconds, in the shop's time zone. Dates are yyyy-MM-dd. */
    fun dayBounds(startIso: String, endIso: String): Pair<Long, Long> {
        val f = AppTime.isoDate()
        val from = f.parse(startIso)!!.time
        val cal = AppTime.calendar()
        cal.timeInMillis = f.parse(endIso)!!.time
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return from to cal.timeInMillis
    }

    /** Start of today in the shop's time zone. */
    fun startOfToday(): Long {
        val cal = AppTime.calendar()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
