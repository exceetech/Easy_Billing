package com.example.easy_billing.correction

import com.example.easy_billing.correction.PurchaseCorrectionMath.LineState
import com.example.easy_billing.correction.PurchaseCorrectionPlanner as P
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PurchaseCorrectionPlannerTest {

    private val item = P.ItemSnap(10.0, 100.0, 118.0, 9.0, 9.0, 0.0, 0.0, 0.0)
    private val itcOn = P.ItcInfo(true)
    private fun line(h: Double = 10.0, s: Double = 10.0, stock: Double = 10.0, avg: Double = 11.8, tracks: Boolean = true) =
        LineState(h, s, stock, avg, tracks)

    private fun ok(r: P.Result): P.Plan =
        if (r is P.Result.Ok) r.plan else throw AssertionError("expected Ok but was $r")

    private fun snap(n: P.NoteRow) =
        P.PriceNoteSnap(n.direction, n.taxable, n.value, n.cess, n.quantity, n.cgstPct, n.sgstPct, n.igstPct)

    @Test fun uncorrectedRatesMatchTheBill() {
        val r = P.currentRates(item, emptyList())
        assertEquals(10.0, r.net, 1e-9); assertEquals(11.8, r.gross, 1e-9)
        assertEquals(0.0, r.cessPerPen, 1e-9); assertEquals(9.0, r.cgstPct, 0.0)
    }

    @Test fun t41_pdnOnFreshBill() {
        val rates = P.currentRates(item, emptyList())
        val plan = ok(P.planPrice(line(), rates, P.PriceRequest(9.0, 9.0, 9.0, 0.0), itcOn))
        assertEquals(1, plan.rows.size)
        val n = plan.rows[0]
        assertEquals("D", n.direction)
        assertEquals(11.80, n.value, 1e-9); assertEquals(10.0, n.taxable, 1e-9)
        assertEquals(0.9, n.cgst, 1e-9); assertEquals(0.9, n.sgst, 1e-9)
        assertEquals(0.9, n.availedCgst, 1e-9)
        assertEquals(0.0, n.variance, 1e-9)
        assertEquals(10.62, plan.correction.newAverage, 1e-9)
        assertNotNull(plan.batchPatch); assertEquals(-1.0, plan.batchPatch!!.netDelta, 1e-9)
        assertEquals(0.0, plan.correction.invariantGap(line()), 1e-9)
    }

    @Test fun pdnTwiceIsAbsoluteSoSecondTapIsNoChange() {
        val rates = P.currentRates(item, emptyList())
        val plan = ok(P.planPrice(line(), rates, P.PriceRequest(9.0, 9.0, 9.0, 0.0), itcOn))
        val after = P.currentRates(item, plan.rows.map(::snap))
        val again = P.planPrice(line(avg = plan.correction.newAverage), after, P.PriceRequest(9.0, 9.0, 9.0, 0.0), itcOn)
        assertTrue(again is P.Result.Blocked)
        assertEquals(PurchaseCorrectionMath.BlockReason.NO_CHANGE, (again as P.Result.Blocked).reason)
    }

    @Test fun partlySoldGivesVariance() {
        val rates = P.currentRates(item, emptyList())
        // 4 of 10 pens still on the shelf; stock includes them only
        val l = line(h = 10.0, s = 4.0, stock = 4.0, avg = 11.8)
        val plan = ok(P.planPrice(l, rates, P.PriceRequest(9.0, 9.0, 9.0, 0.0), itcOn))
        assertEquals(-11.8, plan.rows.sumOf { it.signedValue }, 1e-9)
        assertEquals(4.0 * -1.18, plan.rows[0].shelfValueDelta, 1e-9)
        assertEquals(-11.8 - (4.0 * -1.18), plan.rows[0].variance, 1e-9)
        assertEquals(0.0, plan.correction.invariantGap(l), 1e-9)
    }

    @Test fun mixedDirectionsGiveTwoNotes() {
        val rates = P.currentRates(item, emptyList())
        val plan = ok(P.planPrice(line(), rates, P.PriceRequest(12.0, 2.5, 2.5, 0.0), itcOn))
        assertEquals(2, plan.rows.size)
        val c = plan.rows.first { it.direction == "C" }; val d = plan.rows.first { it.direction == "D" }
        assertEquals(20.0, c.taxable, 1e-9); assertEquals(0.0, c.cgst, 1e-9)
        assertEquals(0.0, d.taxable, 1e-9); assertEquals(12.0, d.cgst + d.sgst, 1e-9)
        assertEquals(8.0, plan.signedNoteValue, 1e-9)
        assertEquals(0.0, plan.correction.invariantGap(line()), 1e-9)
    }

    @Test fun opposingTaxComponentsAreBlocked() {
        val rates = P.currentRates(item, emptyList())
        val r = P.planPrice(line(), rates, P.PriceRequest(10.0, 12.0, 6.0, 0.0), itcOn)
        assertTrue(r is P.Result.Blocked)
    }

    @Test fun ineligibleItcGivesZeroAvailed() {
        val rates = P.currentRates(item, emptyList())
        val plan = ok(P.planPrice(line(), rates, P.PriceRequest(9.0, 9.0, 9.0, 0.0), P.ItcInfo(false)))
        assertEquals(0.0, plan.rows[0].availedCgst, 0.0)
        assertEquals(0.9, plan.rows[0].cgst, 1e-9)
    }

    @Test fun cessFollowsNetRateWhenPercentKept() {
        val it2 = P.ItemSnap(10.0, 100.0, 130.0, 9.0, 9.0, 0.0, 12.0, 12.0)   // 100 + 18 + 12 cess
        val rates = P.currentRates(it2, emptyList())
        val plan = ok(P.planPrice(line(avg = 13.0), rates, P.PriceRequest(9.0, 9.0, 9.0, 0.0), itcOn))
        val n = plan.rows[0]
        assertEquals(1.2, n.cess, 1e-9)
        assertEquals(1.0 * 10 * 1.3, n.value, 1e-9)
    }

    @Test fun qtyDecreaseTakesOldestFirst() {
        val rates = P.currentRates(item, emptyList())
        val l = line(h = 10.0, s = 10.0, stock = 12.0, avg = 11.8)
        val plan = ok(P.planQuantity(l, rates, 6.0, false, itcOn,
            listOf(P.BatchTake(1, 4.0), P.BatchTake(2, 6.0))))
        assertEquals(listOf(P.BatchTake(1, 4.0), P.BatchTake(2, 2.0)), plan.takes)
        val n = plan.rows.single()
        assertEquals("D", n.direction); assertEquals(70.8, n.value, 1e-9); assertEquals(60.0, n.taxable, 1e-9)
        assertEquals(5.4, n.cgst, 1e-9)
    }

    @Test fun qtyIncreaseAddsBatch() {
        val rates = P.currentRates(item, emptyList())
        val plan = ok(P.planQuantity(line(), rates, 3.0, true, itcOn, emptyList()))
        assertEquals("C", plan.rows.single().direction)
        assertNotNull(plan.newBatch); assertEquals(3.0, plan.newBatch!!.newBatchQuantity, 0.0)
        assertTrue(plan.takes.isEmpty())
    }

    @Test fun qtyDecreaseBeyondShelfBlocked() {
        val rates = P.currentRates(item, emptyList())
        val l = line(h = 10.0, s = 3.0, stock = 3.0)
        assertTrue(P.planQuantity(l, rates, 4.0, false, itcOn, listOf(P.BatchTake(1, 3.0))) is P.Result.Blocked)
    }

    @Test fun assetLineHasNoShelfOrVariance() {
        val rates = P.currentRates(item, emptyList())
        val plan = ok(P.planPrice(line(s = 0.0, stock = 0.0, avg = 0.0, tracks = false), rates, P.PriceRequest(9.0, 9.0, 9.0, 0.0), itcOn))
        assertEquals(0.0, plan.rows[0].variance, 0.0); assertNull(plan.batchPatch)
    }

    @Test fun cancelledAndZeroHeldBlocked() {
        val rates = P.currentRates(item, emptyList())
        val req = P.PriceRequest(9.0, 9.0, 9.0, 0.0)
        assertTrue(P.planPrice(LineState(10.0, 10.0, 10.0, 11.8, true, true), rates, req, itcOn) is P.Result.Blocked)
        assertTrue(P.planPrice(LineState(0.0, 0.0, 0.0, 11.8), rates, req, itcOn) is P.Result.Blocked)
    }

    /** Chains of random price notes: filed notes always reproduce the planned rates, invariant holds. */
    @Test fun randomChainsStayConsistent() {
        val rnd = java.util.Random(42)
        repeat(3000) {
            val qty = 1.0 + rnd.nextInt(50)
            val net = 1.0 + rnd.nextInt(2000) / 10.0
            val pct = listOf(0.0, 5.0, 12.0, 18.0, 28.0)[rnd.nextInt(5)]
            val taxable = PurchaseCorrectionMath.round2(qty * net)
            val tax = PurchaseCorrectionMath.round2(taxable * pct / 100.0)
            val it2 = P.ItemSnap(qty, taxable, taxable + tax, pct / 2, pct / 2, 0.0, 0.0, 0.0)
            val notes = ArrayList<P.PriceNoteSnap>()
            var stock = qty + rnd.nextInt(20)
            val shelf = (rnd.nextInt((qty * 2).toInt() + 1) / 2.0).coerceAtMost(qty)
            stock = maxOf(stock, shelf)
            var avg = (taxable + tax) / qty
            repeat(1 + rnd.nextInt(4)) {
                val rates = P.currentRates(it2, notes)
                val newNet = maxOf(0.0, rates.net + (rnd.nextInt(401) - 200) / 100.0)
                val np = listOf(0.0, 5.0, 12.0, 18.0)[rnd.nextInt(4)]
                val l = LineState(qty, shelf, stock, avg)
                val r = P.planPrice(l, rates, P.PriceRequest(newNet, np / 2, np / 2, 0.0), itcOn)
                if (r is P.Result.Ok) {
                    val plan = r.plan
                    assertTrue("gap ${plan.correction.invariantGap(l)}", abs(plan.correction.invariantGap(l)) < 1e-6)
                    assertEquals(plan.correction.signedNoteValue, plan.signedNoteValue, 1e-9)
                    plan.rows.forEach { assertTrue(it.value >= 0 && it.taxable >= 0) }
                    notes.addAll(plan.rows.map(::snap))
                    val after = P.currentRates(it2, notes)
                    assertEquals(plan.newRates.net, after.net, 0.011 / qty)
                    assertEquals(plan.newRates.gross, after.gross, 0.011 / qty)
                    avg = plan.correction.newAverage
                }
            }
        }
    }

    @Test fun bothIgstAndCgstSgstIsRefused() {
        val rates = P.currentRates(item, emptyList())
        val r = P.planPrice(line(), rates, P.PriceRequest(9.0, 9.0, 9.0, 18.0), itcOn)
        assertTrue(r is P.Result.Blocked)
    }

    @Test fun unequalCgstSgstIsRefused() {
        val rates = P.currentRates(item, emptyList())
        val r = P.planPrice(line(), rates, P.PriceRequest(10.0, 12.0, 6.0, 0.0), itcOn)
        assertTrue(r is P.Result.Blocked)
    }

    @Test fun igstOnlyAndEqualCgstSgstStillWork() {
        val igstItem = P.ItemSnap(10.0, 100.0, 118.0, 0.0, 0.0, 18.0, 0.0, 0.0)
        val igstRates = P.currentRates(igstItem, emptyList())
        assertTrue(P.planPrice(line(), igstRates, P.PriceRequest(9.0, 0.0, 0.0, 18.0), itcOn) is P.Result.Ok)
        val rates = P.currentRates(item, emptyList())
        assertTrue(P.planPrice(line(), rates, P.PriceRequest(9.0, 9.0, 9.0, 0.0), itcOn) is P.Result.Ok)
    }
}
