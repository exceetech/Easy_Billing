package com.example.easy_billing.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Payment tracking: split validation, rounding, proportional refunds, ceilings. Pure JVM. */
class PaymentSplitTest {

    private fun L(m: String, a: Double) = PayLine(m, a)

    @Test fun sum_must_match_exactly() {
        val ok = PaymentSplit.validate(500.0, listOf(L("CASH", 300.0), L("UPI", 200.0)))
        assertEquals(SplitCheck.Ok, ok)
        val short = PaymentSplit.validate(500.0, listOf(L("CASH", 300.0), L("UPI", 199.99)))
        assertEquals(SplitCheck.SumMismatch(-0.01), short)
        val over = PaymentSplit.validate(500.0, listOf(L("CASH", 300.0), L("UPI", 200.01)))
        assertEquals(SplitCheck.SumMismatch(0.01), over)
    }

    @Test fun float_noise_does_not_break_the_sum() {
        // 0.1 + 0.2 style noise: 33.33 + 33.33 + 33.34 must settle 100.00
        val r = PaymentSplit.validate(100.0, listOf(L("CASH", 33.33), L("UPI", 33.33), L("CARD", 33.34)))
        assertEquals(SplitCheck.Ok, r)
        assertEquals(SplitCheck.Ok, PaymentSplit.validate(0.3, listOf(L("CASH", 0.1), L("UPI", 0.2))))
    }

    @Test fun bad_lines_are_rejected() {
        assertEquals(SplitCheck.NoLines, PaymentSplit.validate(10.0, emptyList()))
        assertEquals(SplitCheck.NoLines, PaymentSplit.validate(10.0, listOf(L("CASH", 0.0))))
        assertEquals(SplitCheck.BadAmount("CASH"), PaymentSplit.validate(10.0, listOf(L("CASH", -5.0), L("UPI", 15.0))))
        assertEquals(SplitCheck.BadAmount("UPI"), PaymentSplit.validate(10.0, listOf(L("UPI", Double.NaN))))
        assertEquals(SplitCheck.UnknownMode("GOLD"), PaymentSplit.validate(10.0, listOf(L("GOLD", 10.0))))
        // CREDIT is only accepted when the screen allows it
        assertEquals(SplitCheck.UnknownMode("CREDIT"), PaymentSplit.validate(10.0, listOf(L("CREDIT", 10.0)), MoneyMode.ALL))
        assertEquals(SplitCheck.Ok, PaymentSplit.validate(10.0, listOf(L("CREDIT", 10.0)), MoneyMode.ALL + MoneyMode.CREDIT))
    }

    @Test fun same_mode_lines_are_merged() {
        val m = PaymentSplit.merge(listOf(L("CASH", 100.0), L("UPI", 50.0), L("CASH", 25.5), L("CARD", 0.0)))
        assertEquals(listOf(L("CASH", 125.5), L("UPI", 50.0)), m)
        assertEquals(SplitCheck.Ok, PaymentSplit.validate(175.5, listOf(L("CASH", 100.0), L("UPI", 50.0), L("CASH", 25.5))))
    }

    @Test fun last_line_fills_the_remainder() {
        val f = PaymentSplit.fillRemainder(1000.0, listOf(L("CASH", 300.0), L("UPI", 200.0)), MoneyMode.CREDIT)
        assertEquals(listOf(L("CASH", 300.0), L("UPI", 200.0), L("CREDIT", 500.0)), f)
        // others already over the total: no negative fill line
        val over = PaymentSplit.fillRemainder(100.0, listOf(L("CASH", 150.0)), MoneyMode.UPI)
        assertEquals(listOf(L("CASH", 150.0)), over)
        // re-filling replaces the old fill line
        val again = PaymentSplit.fillRemainder(1000.0, listOf(L("CASH", 400.0), L("UPI", 1.0)), MoneyMode.UPI)
        assertEquals(listOf(L("CASH", 400.0), L("UPI", 600.0)), again)
    }

    @Test fun proportional_refund_adds_up_and_keeps_the_ratio() {
        val r = PaymentSplit.proportional(100.0, listOf(L("CASH", 300.0), L("UPI", 200.0)))
        assertEquals(listOf(L("CASH", 60.0), L("UPI", 40.0)), r)
        // awkward thirds: lines still add up to the paisa
        val t = PaymentSplit.proportional(100.0, listOf(L("CASH", 1.0), L("UPI", 1.0), L("CARD", 1.0)))
        assertEquals(100.0, t.sumOf { it.amount }, 0.0)
        assertEquals(listOf(33.33, 33.33, 33.34), t.map { it.amount })
        // one-paisa refund goes to the last line, no zero lines
        val p = PaymentSplit.proportional(0.01, listOf(L("CASH", 1.0), L("UPI", 1.0)))
        assertEquals(listOf(L("UPI", 0.01)), p)
        assertTrue(PaymentSplit.proportional(0.0, listOf(L("CASH", 1.0))).isEmpty())
        assertTrue(PaymentSplit.proportional(10.0, emptyList()).isEmpty())
    }

    @Test fun credit_part_is_separated_from_money() {
        val (money, credit) = PaymentSplit.separateCredit(listOf(L("CASH", 300.0), L("UPI", 200.0), L("CREDIT", 500.0)))
        assertEquals(listOf(L("CASH", 300.0), L("UPI", 200.0)), money)
        assertEquals(500.0, credit, 0.0)
        val (m2, c2) = PaymentSplit.separateCredit(listOf(L("CASH", 50.0)))
        assertEquals(50.0, m2.sumOf { it.amount }, 0.0); assertEquals(0.0, c2, 0.0)
    }

    @Test fun refund_ceiling_never_goes_negative() {
        assertEquals(70.0, PaymentSplit.refundCeiling(100.0, 30.0), 0.0)
        assertEquals(0.0, PaymentSplit.refundCeiling(100.0, 100.0), 0.0)
        assertEquals(0.0, PaymentSplit.refundCeiling(100.0, 130.0), 0.0)
        assertEquals(0.01, PaymentSplit.refundCeiling(0.3, 0.29), 0.0)
    }

    @Test fun mode_groups() {
        assertTrue(!MoneyMode.isBank("CASH"))
        listOf("UPI", "CARD", "BANK").forEach { assertTrue(MoneyMode.isBank(it)) }
        assertTrue(!MoneyMode.isValid("CREDIT"))     // credit is not a money mode
        assertEquals("OUT", MoneyDirection.opposite("IN"))
    }

    /** Random check: whatever the numbers, split / fill / proportional always add up to the paisa. */
    @Test fun random_simulation_always_adds_up() {
        val rnd = Random(42)
        repeat(5000) {
            val total = PaymentSplit.fromPaise((rnd.nextInt(5_000_000) + 1).toLong())
            val n = 1 + rnd.nextInt(3)
            val modes = MoneyMode.ALL.shuffled(rnd).take(n)
            val lines = ArrayList<PayLine>()
            var left = PaymentSplit.toPaise(total)
            for ((i, m) in modes.withIndex()) {
                val part = if (i == modes.lastIndex) left else (rnd.nextDouble() * left).toLong()
                left -= part
                lines += PayLine(m, PaymentSplit.fromPaise(part))
            }
            assertEquals(SplitCheck.Ok, PaymentSplit.validate(total, lines))

            val refund = PaymentSplit.fromPaise((rnd.nextDouble() * PaymentSplit.toPaise(total)).toLong() + 1)
            val r = PaymentSplit.proportional(refund, lines)
            if (PaymentSplit.toPaise(refund) > 0 && PaymentSplit.merge(lines).isNotEmpty()) {
                assertEquals(PaymentSplit.toPaise(refund), r.sumOf { PaymentSplit.toPaise(it.amount) })
                assertTrue(r.all { PaymentSplit.toPaise(it.amount) > 0 })
            }
            val filled = PaymentSplit.fillRemainder(total, lines.dropLast(1), lines.last().mode)
            assertEquals(PaymentSplit.toPaise(total), filled.sumOf { PaymentSplit.toPaise(it.amount) })
        }
    }

    @Test fun payment_method_text() {
        val t = PaymentMethodText
        assertEquals("CASH+UPI+CREDIT", t.encode(listOf(L("CREDIT", 5.0), L("UPI", 2.0), L("CASH", 3.0)), "CASH"))
        assertEquals("UPI", t.encode(listOf(L("UPI", 10.0)), "CASH"))
        assertEquals("CASH", t.encode(emptyList(), "CASH"))
        assertEquals("CASH+CARD", t.encode(listOf(L("CARD", 1.0), L("CASH", 1.0), L("UPI", 0.0)), "CASH"))
        assertTrue(t.hasCredit("CASH+CREDIT") && t.hasCredit("CREDIT") && !t.hasCredit("CASH+UPI"))
        assertTrue(t.isPureUpi("UPI") && t.isPureUpi("upi") && !t.isPureUpi("CASH+UPI"))
        assertTrue(t.isSplit("CASH+UPI") && !t.isSplit("CASH"))
        assertEquals("Cash + UPI + Credit", t.label("CASH+UPI+CREDIT"))
        assertEquals("CASH", t.label("CASH"))
        // old rows written by older versions
        assertTrue(t.hasCredit("Credit") && t.hasCredit("credit"))
    }

    @Test fun allocate_cuts_lines_in_order() {
        val (a, b) = PaymentSplit.allocate(listOf(L("CASH", 60.0), L("UPI", 40.0)), 70.0)
        assertEquals(listOf(L("CASH", 60.0), L("UPI", 10.0)), a)
        assertEquals(listOf(L("UPI", 30.0)), b)
        val (a2, b2) = PaymentSplit.allocate(listOf(L("CASH", 50.0)), 50.0)
        assertEquals(listOf(L("CASH", 50.0)), a2); assertTrue(b2.isEmpty())
        val (a3, b3) = PaymentSplit.allocate(listOf(L("CASH", 50.0)), 0.0)
        assertTrue(a3.isEmpty()); assertEquals(listOf(L("CASH", 50.0)), b3)
        // random: both parts always add up to the whole, to the paisa
        val rnd = Random(7)
        repeat(2000) {
            val lines = MoneyMode.ALL.take(1 + rnd.nextInt(4)).map { L(it, PaymentSplit.fromPaise(1L + rnd.nextInt(100000))) }
            val whole = lines.sumOf { PaymentSplit.toPaise(it.amount) }
            val first = PaymentSplit.fromPaise((rnd.nextDouble() * whole).toLong())
            val (x, y) = PaymentSplit.allocate(lines, first)
            assertEquals(PaymentSplit.toPaise(first), x.sumOf { PaymentSplit.toPaise(it.amount) })
            assertEquals(whole, (x + y).sumOf { PaymentSplit.toPaise(it.amount) })
        }
    }

    @Test fun cancelled_status_is_valid() {
        assertTrue(MoneyStatus.isValid("CANCELLED") && MoneyStatus.isValid("PENDING") && !MoneyStatus.isValid("X"))
    }

    @Test fun credit_side_from_history() {
        val S = CreditSide.Side.values()
        assertEquals(CreditSide.Side.CUSTOMER, CreditSide.of(listOf("ADD", "PAY")))
        assertEquals(CreditSide.Side.CUSTOMER, CreditSide.of(listOf("SALE_RETURN")))
        assertEquals(CreditSide.Side.SUPPLIER, CreditSide.of(listOf("PURCHASE_CREDIT", "PAY", "PURCHASE_RETURN")))
        assertEquals(CreditSide.Side.UNKNOWN, CreditSide.of(emptyList()))          // brand-new account
        assertEquals(CreditSide.Side.UNKNOWN, CreditSide.of(listOf("PAY")))        // only payments so far
        assertEquals(CreditSide.Side.UNKNOWN, CreditSide.of(listOf("ADD", "PURCHASE_CREDIT")))   // mixed
        assertEquals("IN", CreditSide.payDirection(CreditSide.Side.CUSTOMER))
        assertEquals("OUT", CreditSide.payDirection(CreditSide.Side.SUPPLIER))
        assertEquals(null, CreditSide.payDirection(CreditSide.Side.UNKNOWN))
        assertEquals("OUT", CreditSide.refundDirection(CreditSide.Side.CUSTOMER))
        assertEquals("IN", CreditSide.refundDirection(CreditSide.Side.SUPPLIER))
        assertEquals(MoneyReason.DUES_RECEIVED, CreditSide.payReason("IN"))
        assertEquals(MoneyReason.SUPPLIER_PAID, CreditSide.payReason("OUT"))
        assertEquals(MoneyReason.ADVANCE_REFUND, CreditSide.refundReason("OUT"))
        assertEquals(MoneyReason.PURCHASE_REFUND, CreditSide.refundReason("IN"))
        assertEquals(3, S.size)
    }
}
