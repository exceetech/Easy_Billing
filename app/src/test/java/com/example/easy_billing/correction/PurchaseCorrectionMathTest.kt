package com.example.easy_billing.correction

import com.example.easy_billing.correction.PurchaseCorrectionMath.BlockReason
import com.example.easy_billing.correction.PurchaseCorrectionMath.Correction
import com.example.easy_billing.correction.PurchaseCorrectionMath.LineState
import com.example.easy_billing.correction.PurchaseCorrectionMath.Outcome
import com.example.easy_billing.correction.PurchaseCorrectionMath.PriceChange
import com.example.easy_billing.correction.PurchaseCorrectionMath.QtyChange
import com.example.easy_billing.correction.PurchaseCorrectionMath.grossRate
import com.example.easy_billing.correction.PurchaseCorrectionMath.round2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/** Phase 3 tests T3-1 .. T3-12 plus guards. Pure JVM, no Android. */
class PurchaseCorrectionMathTest {

    private val D = 1e-6

    private fun ok(o: Outcome): Correction {
        if (o is Outcome.Ok) return o.correction
        fail("expected Ok but was $o")
        throw IllegalStateException()
    }

    private fun blockedReason(o: Outcome): BlockReason {
        if (o is Outcome.Blocked) return o.reason
        fail("expected Blocked but was $o")
        throw IllegalStateException()
    }

    // 10.00 net at 18% = 11.80 gross
    private val n0 = 10.0
    private val g0 = 11.80

    private fun price(oldN: Double, oldG: Double, newN: Double, newG: Double) = PriceChange(oldN, oldG, newN, newG)

    // T3-1 PDN down, nothing sold
    @Test fun t3_1_pdnDownNothingSold() {
        val line = LineState(held = 100.0, shelf = 100.0, stock = 100.0, averageCost = 11.80)
        val c = ok(PurchaseCorrectionMath.priceCorrection(line, price(10.0, 11.80, 9.0, 10.62)))
        assertEquals(-118.0, c.signedNoteValue, D)
        assertEquals(10.62, c.newAverage, D)
        assertEquals(0.0, c.totalVariance, D)
        assertEquals("D", c.parts.single().direction)
        assertEquals(100.0, c.parts.single().taxable, D)      // taxable = H x 1.00
        assertEquals(18.0, c.parts.single().tax, D)           // tax = H x 0.18
        assertEquals("COST_ADJUST", c.log!!.type)
        assertEquals(0.0, c.invariantGap(line), D)
    }

    // T3-2 PDN down, partly sold
    @Test fun t3_2_pdnDownPartlySold() {
        val line = LineState(100.0, 40.0, 40.0, 11.80)
        val c = ok(PurchaseCorrectionMath.priceCorrection(line, price(10.0, 11.80, 9.0, 10.62)))
        assertEquals(-47.20, c.totalShelfValueDelta, D)
        assertEquals(-70.80, c.totalVariance, D)           // gain
        assertEquals(10.62, c.newAverage, D)
        assertEquals(0.0, c.invariantGap(line), D)
    }

    // T3-3 PDN up, all sold
    @Test fun t3_3_pdnUpAllSold() {
        val line = LineState(100.0, 0.0, 30.0, 12.0)       // 30 pens from other bills still in stock
        val c = ok(PurchaseCorrectionMath.priceCorrection(line, price(10.0, 11.80, 11.0, 12.98)))
        assertEquals(118.0, c.signedNoteValue, D)
        assertEquals(118.0, c.totalVariance, D)            // loss
        assertEquals(12.0, c.newAverage, D)                // average untouched
        assertNull(c.log)
        assertEquals("C", c.parts.single().direction)
        assertEquals(0.0, c.invariantGap(line), D)
    }

    // T3-4 PDN after a return: 100 bought, 60 sold, 20 returned -> H=80, S=20
    @Test fun t3_4_pdnAfterReturn() {
        val line = LineState(80.0, 20.0, 20.0, 11.80)
        val c = ok(PurchaseCorrectionMath.priceCorrection(line, price(10.0, 11.80, 9.0, 10.62)))
        assertEquals(-94.40, c.signedNoteValue, D)
        assertEquals(-23.60, c.totalShelfValueDelta, D)
        assertEquals(-70.80, c.totalVariance, D)
        assertEquals(10.62, c.newAverage, D)
        assertEquals(0.0, c.invariantGap(line), D)
    }

    // T3-5 GST-rate-only change 18% -> 12% on 10.00 net
    @Test fun t3_5_gstRateOnlyChange() {
        val line = LineState(100.0, 100.0, 100.0, 11.80)
        val c = ok(PurchaseCorrectionMath.priceCorrection(line, price(10.0, grossRate(10.0, 18.0), 10.0, grossRate(10.0, 12.0))))
        val p = c.parts.single()
        assertEquals(-60.0, c.signedNoteValue, D)          // 100 x -0.60
        assertEquals(0.0, p.taxable, D)
        assertEquals(60.0, p.tax, D)
        assertEquals(0.0, p.rateDeltaNet, D)
        assertEquals(0.0, c.invariantGap(line), D)
    }

    // T3-6 taxable up, tax down -> two notes, each single-signed (D11)
    @Test fun t3_6_oppositeDirectionsMakeTwoNotes() {
        val line = LineState(100.0, 60.0, 60.0, 11.80)
        // net 10 -> 11, rate 18% -> 5%: gross 11.80 -> 11.55
        val c = ok(PurchaseCorrectionMath.priceCorrection(line, price(10.0, 11.80, 11.0, grossRate(11.0, 5.0))))
        assertEquals(2, c.parts.size)
        val taxableNote = c.parts[0]
        val taxNote = c.parts[1]
        assertEquals("C", taxableNote.direction); assertEquals(100.0, taxableNote.value, D); assertEquals(0.0, taxableNote.tax, D)
        assertEquals("D", taxNote.direction);     assertEquals(125.0, taxNote.value, D);     assertEquals(0.0, taxNote.taxable, D)
        assertEquals(-25.0, c.signedNoteValue, D)
        assertEquals(0.0, c.invariantGap(line), D)
        // shelf parts add up to what moved the average
        assertEquals(round2(60.0 * (11.55 - 11.80)), c.totalShelfValueDelta, D)
    }

    // T3-7 QDN -10 with 90 on the shelf
    @Test fun t3_7_qdnDecrease() {
        val line = LineState(100.0, 90.0, 90.0, 11.80)
        val c = ok(PurchaseCorrectionMath.quantityCorrection(line, QtyChange(10.0, false, n0, g0)))
        assertEquals(11.80, c.newAverage, D)
        assertEquals(80.0, c.newStock, D)
        assertEquals(0.0, c.totalVariance, D)
        assertEquals(-118.0, c.signedNoteValue, D)
        assertEquals("QTY_CORR_REDUCE", c.log!!.type)
        assertEquals(-10.0, c.batch.quantityRemainingDelta, D)
        assertEquals(0.0, c.invariantGap(line), D)
    }

    // T3-8 QDN -10 with only 5 on the shelf -> blocked (D5)
    @Test fun t3_8_qdnBeyondShelfBlocked() {
        val line = LineState(100.0, 5.0, 5.0, 11.80)
        assertEquals(BlockReason.QDN_EXCEEDS_SHELF,
            blockedReason(PurchaseCorrectionMath.quantityCorrection(line, QtyChange(10.0, false, n0, g0))))
    }

    // T3-9 stock 0 -> no division by zero, everything to variance
    @Test fun t3_9_zeroStockSafe() {
        val line = LineState(100.0, 0.0, 0.0, 0.0)
        val c = ok(PurchaseCorrectionMath.priceCorrection(line, price(10.0, 11.80, 9.0, 10.62)))
        assertEquals(-118.0, c.totalVariance, D)
        assertEquals(0.0, c.newAverage, D)
        assertNull(c.log)
        assertEquals(0.0, c.invariantGap(line), D)
    }

    // T3-10 PDN that would make the average negative -> blocked
    @Test fun t3_10_negativeAverageBlocked() {
        val line = LineState(100.0, 10.0, 10.0, 1.0)       // 10 pens worth 1.00 each
        assertEquals(BlockReason.NEGATIVE_AVERAGE,
            blockedReason(PurchaseCorrectionMath.priceCorrection(line, price(10.0, 11.80, 9.0, 8.0))))
    }

    // T3-12 rounding: 3 lines, the leftover paisa lands on the last line
    @Test fun t3_12_remainderOnLastLine() {
        val parts = PurchaseCorrectionMath.splitAcrossLines(100.0, listOf(1.0, 1.0, 1.0))
        assertEquals(listOf(33.33, 33.33, 33.34), parts)
        assertEquals(100.0, round2(parts.sum()), D)
        val odd = PurchaseCorrectionMath.splitAcrossLines(-0.05, listOf(3.0, 3.0, 3.0))
        assertEquals(-0.05, round2(odd.sum()), D)
    }

    // ───────── more cases ─────────

    @Test fun qdnIncreaseAddsBatchAndRaisesAverage() {
        val line = LineState(100.0, 100.0, 100.0, 11.80)
        val c = ok(PurchaseCorrectionMath.quantityCorrection(line, QtyChange(10.0, true, 11.0, 12.98)))
        assertEquals(110.0, c.newStock, D)
        assertEquals((100 * 11.80 + 129.80) / 110.0, c.newAverage, D)
        assertEquals("QTY_CORR_ADD", c.log!!.type)
        assertEquals(10.0, c.batch.newBatchQuantity, D)
        assertEquals("C", c.parts.single().direction)
        assertEquals(0.0, c.invariantGap(line), D)
    }

    @Test fun qdnDecreaseWholeStockEmptiesShelf() {
        val line = LineState(10.0, 10.0, 10.0, 11.80)
        val c = ok(PurchaseCorrectionMath.quantityCorrection(line, QtyChange(10.0, false, n0, g0)))
        assertEquals(0.0, c.newStock, D)
        assertEquals(0.0, c.newAverage, D)
        assertEquals(0.0, c.invariantGap(line), 0.006)
    }

    @Test fun nonStockLineGetsValueAndGstOnly() {
        val line = LineState(1.0, 0.0, 0.0, 0.0, tracksStock = false)
        val c = ok(PurchaseCorrectionMath.priceCorrection(line, price(1000.0, 1180.0, 900.0, 1062.0)))
        assertEquals(-118.0, c.signedNoteValue, D)
        assertEquals(0.0, c.totalVariance, D)
        assertEquals(0.0, c.totalShelfValueDelta, D)
        assertNull(c.log)
    }

    @Test fun guards() {
        val base = LineState(100.0, 100.0, 100.0, 11.80)
        val chg = price(10.0, 11.80, 9.0, 10.62)
        assertEquals(BlockReason.CANCELLED_PURCHASE,
            blockedReason(PurchaseCorrectionMath.priceCorrection(base.copy(purchaseCancelled = true), chg)))
        assertEquals(BlockReason.NOTHING_HELD,
            blockedReason(PurchaseCorrectionMath.priceCorrection(base.copy(held = 0.0, shelf = 0.0), chg)))
        assertEquals(BlockReason.NO_CHANGE,
            blockedReason(PurchaseCorrectionMath.priceCorrection(base, price(10.0, 11.80, 10.0, 11.80))))
        assertEquals(BlockReason.INVALID_INPUT,
            blockedReason(PurchaseCorrectionMath.priceCorrection(base.copy(shelf = 150.0), chg)))
        assertEquals(BlockReason.INVALID_INPUT,
            blockedReason(PurchaseCorrectionMath.priceCorrection(base, price(10.0, Double.NaN, 9.0, 10.62))))
        assertEquals(BlockReason.INVALID_INPUT,
            blockedReason(PurchaseCorrectionMath.quantityCorrection(base, QtyChange(0.0, true, n0, g0))))
        assertEquals(BlockReason.NOTHING_HELD,
            blockedReason(PurchaseCorrectionMath.quantityCorrection(base.copy(held = 0.0, shelf = 0.0), QtyChange(1.0, true, n0, g0))))
    }

    // Worked example 1 from the plan: 100 bought, 60 sold, 20 returned -> PDN to Rs 9 = 94.40
    @Test fun planExample1BillStandsAt849_60() {
        val line = LineState(80.0, 20.0, 20.0, 11.80)
        val c = ok(PurchaseCorrectionMath.priceCorrection(line, price(10.0, 11.80, 9.0, 10.62)))
        assertEquals(849.60, 1180.0 - 236.0 + c.signedNoteValue, D)
    }

    // ───────── T3-11: 10,000 random cases, invariant to the paisa ─────────

    @Test fun t3_11_randomPriceCorrections() {
        val rnd = Random(20261009L)
        val rates = doubleArrayOf(0.0, 5.0, 12.0, 18.0, 28.0)
        var okCount = 0
        var blockedCount = 0
        repeat(10_000) { i ->
            val stock = (rnd.nextInt(500)).toDouble()
            val avg = round2(rnd.nextDouble() * 60.0)
            val held = (1 + rnd.nextInt(300)).toDouble()
            val shelf = minOf(held, stock, rnd.nextInt(301).toDouble())
            val oldN = round2(0.5 + rnd.nextDouble() * 99.5)
            val oldG = grossRate(oldN, rates[rnd.nextInt(rates.size)])
            val newN = if (rnd.nextBoolean()) oldN else round2(maxOf(0.0, oldN + (rnd.nextDouble() - 0.5) * 20.0))
            val newG = grossRate(newN, rates[rnd.nextInt(rates.size)])
            val line = LineState(held, shelf, stock, avg)
            val o = PurchaseCorrectionMath.priceCorrection(line, price(oldN, oldG, newN, newG))
            if (o is Outcome.Ok) {
                okCount++
                val c = o.correction
                assertEquals("case $i invariant", 0.0, c.invariantGap(line), 1e-6)
                assertTrue("case $i average negative", c.newAverage >= 0.0)
                assertEquals("case $i stock unchanged", stock, c.newStock, 0.0)
                c.parts.forEach { p ->
                    assertTrue("case $i part sign", p.value > 0.0)
                    assertEquals("case $i value split", p.value, p.taxable + p.tax, 0.0101)
                    assertTrue(p.direction == "D" || p.direction == "C")
                }
                assertEquals("case $i total", round2(held * (newG - oldG)), c.signedNoteValue, 0.0101)
                if (c.parts.size == 2) assertTrue("two parts differ in direction", c.parts[0].direction != c.parts[1].direction)
            } else if (o is Outcome.Blocked) {
                blockedCount++
                assertTrue("case $i unexpected block ${o.reason}",
                    o.reason == BlockReason.NO_CHANGE || o.reason == BlockReason.NEGATIVE_AVERAGE)
            }
        }
        assertTrue("too few ok cases: $okCount", okCount > 7_000)
        println("T3-11 price: ok=$okCount blocked=$blockedCount")
    }

    @Test fun t3_11_randomQuantityCorrections() {
        val rnd = Random(77L)
        var okCount = 0
        repeat(10_000) { i ->
            val stock = (1 + rnd.nextInt(500)).toDouble()
            val avg = round2(0.5 + rnd.nextDouble() * 60.0)
            val held = (1 + rnd.nextInt(300)).toDouble()
            val shelf = minOf(held, stock, rnd.nextInt(301).toDouble())
            val n = round2(0.5 + rnd.nextDouble() * 99.5)
            val g = grossRate(n, listOf(0.0, 5.0, 12.0, 18.0, 28.0)[rnd.nextInt(5)])
            val inc = rnd.nextBoolean()
            val pens = (1 + rnd.nextInt(120)).toDouble()
            val line = LineState(held, shelf, stock, avg)
            val o = PurchaseCorrectionMath.quantityCorrection(line, QtyChange(pens, inc, n, g))
            if (o is Outcome.Ok) {
                okCount++
                val c = o.correction
                val emptied = c.newStock == 0.0
                assertEquals("case $i invariant", 0.0, c.invariantGap(line), if (emptied) 0.0101 else 1e-6)
                assertTrue("case $i average negative", c.newAverage >= 0.0)
                assertEquals("case $i stock", if (inc) stock + pens else stock - pens, c.newStock, 1e-9)
                assertEquals(if (inc) "C" else "D", c.parts.single().direction)
                assertNotNull(c.log)
            } else if (o is Outcome.Blocked) {
                assertTrue("case $i unexpected ${o.reason}",
                    (!inc && o.reason == BlockReason.QDN_EXCEEDS_SHELF) ||
                        o.reason == BlockReason.NEGATIVE_AVERAGE ||
                        o.reason == BlockReason.NOTHING_HELD)
            }
        }
        assertTrue("too few ok: $okCount", okCount > 3_000)
        println("T3-11 qty: ok=$okCount")
    }
}
