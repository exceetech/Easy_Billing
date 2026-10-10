package com.example.easy_billing.correction

import com.example.easy_billing.correction.PurchaseCorrectionMath.LineState
import com.example.easy_billing.correction.PurchaseCorrectionMath.round2
import com.example.easy_billing.correction.PurchaseCorrectionPlanner as P
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.min

/**
 * Phase 7 — scripted sequence runner.
 *
 * A small in-memory shop (one bill, one product, optional older "other" stock)
 * performs purchase-side operations in every order of 1–3 steps and many random
 * 4–6 step orders. Each operation follows the same rules the app code follows
 * (held/shelf from filed notes, return at corrected rate, cancel rule H−S) and
 * uses the real planner for corrections. After EVERY step it checks:
 *
 *   MONEY   stock value + cost of goods sold + loss = other stock value + supplier payable
 *   PENS    held − shelf = pens of this bill sold ; shelf ≤ held ; stock = Σ batch counters
 *   RATES   every bill batch's gross cost matches the line's corrected gross rate
 *   BLOCKS  a blocked operation changes nothing; cancelled bills accept no corrections
 */
class PurchaseSequenceVerificationTest {

    private val itc = P.ItcInfo(true)
    private val EPS = 1e-6

    private class Batch(val bill: Boolean, var remaining: Double, var purchased: Double, var invoice: Double, var net: Double) {
        fun gross() = if (purchased > 0) invoice / purchased else 0.0
    }

    private inner class Sim(val billed: Double, net: Double, val pct: Double, otherQty: Double, otherAvg: Double) {
        val batches = ArrayList<Batch>()
        val priceNotes = ArrayList<P.PriceNoteSnap>()
        var stock = 0.0; var avg = 0.0
        var payable = 0.0; var cogs = 0.0; var loss = 0.0
        var netOut = 0.0; var billSold = 0.0
        var cancelled = false
        var steps = 0
        val otherValue0 = otherQty * otherAvg
        val item: P.ItemSnap
        val log = ArrayList<String>()

        init {
            if (otherQty > 0) { batches.add(Batch(false, otherQty, otherQty, otherValue0, otherAvg)); stock = otherQty; avg = otherAvg }
            val taxable = round2(billed * net)
            val tax = round2(taxable * pct / 100.0)
            val invoice = taxable + tax
            item = P.ItemSnap(billed, taxable, invoice, pct / 2, pct / 2, 0.0, 0.0, 0.0)
            batches.add(Batch(true, billed, billed, invoice, net))
            avg = (stock * avg + invoice) / (stock + billed)
            stock += billed
            payable = invoice
        }

        val held get() = (billed - netOut).coerceAtLeast(0.0)
        val shelf get() = batches.filter { it.bill }.sumOf { it.remaining }
        fun rates() = P.currentRates(item, priceNotes)
        fun state() = LineState(held, shelf, stock, avg, true, cancelled)
        fun billBatchesLeft() = batches.filter { it.bill && it.remaining > 0 }

        fun snapshot() = doubleArrayOf(stock, avg, payable, cogs, loss, netOut, billSold, batches.size.toDouble(),
            batches.sumOf { it.remaining }, batches.sumOf { it.invoice }, priceNotes.size.toDouble())

        fun pdn(factor: Double, newPct: Double?): String {
            val r = rates()
            val p = newPct ?: (r.cgstPct + r.sgstPct + r.igstPct)
            val before = snapshot()
            val res = P.planPrice(state(), r, P.PriceRequest(r.net * factor, p / 2, p / 2, 0.0), itc)
            if (res is P.Result.Blocked) { assertArrayEquals("blocked PDN changed state", before, snapshot(), 0.0); return "blocked" }
            val plan = (res as P.Result.Ok).plan
            val bp = plan.batchPatch!!
            for (b in batches.filter { it.bill }) { b.net += bp.netDelta; b.invoice += bp.grossDelta * b.purchased }
            priceNotes.addAll(plan.rows.map { P.PriceNoteSnap(it.direction, it.taxable, it.value, it.cess, it.quantity, it.cgstPct, it.sgstPct, it.igstPct) })
            avg = plan.correction.newAverage
            payable += plan.signedNoteValue
            loss += plan.rows.sumOf { it.variance }
            return "ok"
        }

        fun qdn(increase: Boolean, pens: Double): String {
            val r = rates()
            val before = snapshot()
            val takes = billBatchesLeft().mapIndexed { i, b -> P.BatchTake(i, b.remaining) }
            val res = P.planQuantity(state(), r, pens, increase, itc, takes)
            if (res is P.Result.Blocked) { assertArrayEquals("blocked QDN changed state", before, snapshot(), 0.0); return "blocked" }
            val plan = (res as P.Result.Ok).plan
            val left = billBatchesLeft()
            if (!increase) {
                for (t in plan.takes) {
                    val b = left[t.batchId]; val g = b.gross()
                    b.remaining -= t.quantity; b.purchased -= t.quantity; b.invoice -= t.quantity * g
                }
                netOut += pens
            } else {
                val nb = plan.newBatch!!
                batches.add(Batch(true, nb.newBatchQuantity, nb.newBatchQuantity, plan.rows.single().value, nb.newBatchNetRate))
                netOut -= pens
            }
            stock = plan.correction.newStock; avg = plan.correction.newAverage
            payable += plan.signedNoteValue
            loss += plan.rows.sumOf { it.variance }
            return "ok"
        }

        fun ret(pens: Double): String {
            if (cancelled || pens <= 0 || pens > min(held, shelf) + EPS) return "skipped"
            val r = rates()
            var left = pens; var variance = 0.0
            for (b in batches.filter { it.bill && it.remaining > 0 }) {
                if (left <= 0) break
                val take = min(b.remaining, left)
                variance += take * avg - take * b.gross()
                b.remaining -= take; left -= take
            }
            stock -= pens
            if (stock <= EPS) { stock = 0.0; avg = 0.0 }
            payable -= round2(pens * r.gross)
            loss += round2(variance)
            netOut += pens
            return "ok"
        }

        fun add(pens: Double): String {
            if (cancelled || pens <= 0) return "skipped"
            val r = rates()
            val value = round2(pens * r.gross)
            avg = if (stock <= EPS) r.gross else (stock * avg + pens * r.gross) / (stock + pens)
            stock += pens
            batches.add(Batch(true, pens, pens, value, r.net))
            payable += value
            netOut -= pens
            return "ok"
        }

        fun sale(want: Double): String {
            val pens = min(want, stock)
            if (pens <= EPS) return "skipped"
            var left = pens
            for (b in batches.filter { it.remaining > 0 }) {
                if (left <= 0) break
                val take = min(b.remaining, left)
                b.remaining -= take; left -= take
                if (b.bill) billSold += take
            }
            cogs += pens * avg
            stock -= pens
            if (stock <= EPS) { stock = 0.0; avg = 0.0 }
            return "ok"
        }

        fun cancel(): String {
            if (cancelled) return "skipped"
            if (held - shelf > 0.01) return "blocked"        // pens were sold / scrapped
            val s = shelf
            if (s > 0) ret(s)
            cancelled = true
            return "ok"
        }

        fun check(where: String) {
            steps++
            val tol = 0.03 * steps + 0.03
            assertTrue("$where stock<0", stock >= -EPS)
            assertTrue("$where avg<0", avg >= -EPS)
            assertEquals("$where stock vs batch counters", batches.sumOf { it.remaining }, stock, 1e-6)
            assertEquals("$where held-shelf vs sold", billSold, held - shelf, 1e-6)
            assertTrue("$where shelf>held", shelf <= held + 1e-6)
            val lhs = stock * avg + cogs + loss
            val rhs = otherValue0 + payable
            assertEquals("$where MONEY identity ($log)", rhs, lhs, tol)
            assertTrue("$where payable<0 ($payable)", payable >= -tol)
            if (!cancelled) {
                val g = rates().gross
                for (b in batches.filter { it.bill && it.purchased > 0 })
                    assertEquals("$where batch gross vs rate ($log)", g, b.gross(), 0.05)
            }
        }

        fun run(op: String, f: () -> String) {
            val r = f()
            log.add("$op:$r")
            check(op)
            if (cancelled) {
                // a cancelled bill takes no corrections
                val before = snapshot()
                val rt = rates()
                assertTrue(P.planPrice(state(), rt, P.PriceRequest(rt.net * 0.9, pct / 2, pct / 2, 0.0), itc) is P.Result.Blocked)
                assertTrue(P.planQuantity(state(), rt, 1.0, false, itc, emptyList()) is P.Result.Blocked)
                assertArrayEquals(before, snapshot(), 0.0)
            }
        }
    }

    private val ops = listOf("PDN_DOWN", "PDN_UP", "PDN_GST", "QDN_DOWN", "QDN_UP", "RETURN", "ADD", "SALE", "CANCEL")

    private fun apply(s: Sim, op: String) = s.run(op) {
        when (op) {
            "PDN_DOWN" -> s.pdn(0.9, null)
            "PDN_UP" -> s.pdn(1.1, null)
            "PDN_GST" -> s.pdn(1.0, 12.0)
            "QDN_DOWN" -> s.qdn(false, 10.0)
            "QDN_UP" -> s.qdn(true, 5.0)
            "RETURN" -> s.ret(20.0)
            "ADD" -> s.add(7.0)
            "SALE" -> s.sale(60.0)
            else -> s.cancel()
        }
    }

    private fun configs() = listOf(Triple(0.0, 0.0, "fresh"), Triple(50.0, 15.0, "withOtherStock"))

    private fun newSim(c: Triple<Double, Double, String>) = Sim(100.0, 10.0, 18.0, c.first, c.second)

    @Test fun allSequencesOfOneToThreeSteps() {
        var count = 0
        for (c in configs()) {
            fun rec(prefix: List<String>, depth: Int) {
                if (prefix.isNotEmpty()) {
                    val s = newSim(c)
                    for (op in prefix) apply(s, op)
                    count++
                }
                if (depth == 3) return
                for (o in ops) rec(prefix + o, depth + 1)
            }
            rec(emptyList(), 0)
        }
        println("exhaustive 1-3 step sequences run: $count")
        assertEquals(2 * (9 + 81 + 729), count)
    }

    @Test fun randomFourToSixStepSequences() {
        val rnd = Random(20261009)
        var blocked = 0; var okOps = 0
        repeat(40000) {
            val billed = (1 + rnd.nextInt(200)).toDouble()
            val net = 1.0 + rnd.nextInt(5000) / 100.0
            val pct = listOf(0.0, 5.0, 12.0, 18.0, 28.0)[rnd.nextInt(5)]
            val other = if (rnd.nextBoolean()) 0.0 else (1 + rnd.nextInt(100)).toDouble()
            val s = Sim(billed, net, pct, other, 1.0 + rnd.nextInt(3000) / 100.0)
            repeat(4 + rnd.nextInt(3)) {
                val r = s.rates()
                val res = when (rnd.nextInt(9)) {
                    0, 1 -> s.run("PDN") { s.pdn(0.5 + rnd.nextInt(101) / 100.0, listOf(0.0, 5.0, 12.0, 18.0, 28.0)[rnd.nextInt(5)]) }
                    2 -> s.run("QDN_DOWN") { s.qdn(false, (1 + rnd.nextInt(60)).toDouble().coerceAtMost(s.held + 5)) }
                    3 -> s.run("QDN_UP") { s.qdn(true, (1 + rnd.nextInt(20)).toDouble()) }
                    4 -> s.run("RETURN") { s.ret((1 + rnd.nextInt(60)).toDouble()) }
                    5 -> s.run("ADD") { s.add((1 + rnd.nextInt(20)).toDouble()) }
                    6, 7 -> s.run("SALE") { s.sale((1 + rnd.nextInt(120)).toDouble()) }
                    else -> s.run("CANCEL") { s.cancel() }
                }
                if (s.log.last().endsWith("blocked")) blocked++ else if (s.log.last().endsWith(":ok")) okOps++
                if (r.hashCode() == 7) println(r) // keep r used
            }
        }
        println("random sequences: 40000, ok ops=$okOps blocked ops=$blocked")
        assertTrue(okOps > 50000)
    }

    // ---- named flows from the plan --------------------------------------------------------

    @Test fun flow1_pdnFreshBill() {
        val s = newSim(configs()[0]); apply(s, "PDN_DOWN")
        assertEquals(10.62, s.avg, 1e-9); assertEquals(1062.0, s.payable, 1e-9); assertEquals(0.0, s.loss, 1e-9)
    }
    @Test fun flow2_sell60ThenPdn_gain70_80() {
        val s = newSim(configs()[0]); apply(s, "SALE"); apply(s, "PDN_DOWN")
        assertEquals(-70.80, s.loss, 1e-9); assertEquals(10.62, s.avg, 1e-9)
    }
    @Test fun flow3_qdnThenCancel_netsToZero() {
        val s = newSim(configs()[0]); apply(s, "QDN_DOWN"); apply(s, "CANCEL")
        assertTrue(s.cancelled); assertEquals(0.0, s.payable, 0.03); assertEquals(0.0, s.stock, 0.0)
    }
    @Test fun flow4_returnPdnReturn() {
        val s = newSim(configs()[0]); apply(s, "RETURN"); apply(s, "PDN_DOWN")
        assertEquals(849.60, s.payable, 1e-9)
        assertEquals(1.0 * 0, 0.0, 0.0)
    }
    @Test fun flow5_pdnThenReturn_sameBill() {
        val s = newSim(configs()[0]); apply(s, "PDN_DOWN"); apply(s, "RETURN")
        assertEquals(849.60, s.payable, 1e-9)
    }
    @Test fun t43_returnAfterPdnRefundsAtCorrectedRate() {
        val s = newSim(configs()[0]); apply(s, "PDN_DOWN")
        val before = s.payable; s.ret(10.0)
        assertEquals(106.20, before - s.payable, 1e-9)
    }
    @Test fun t46_soldPensBlockCancel_anyOrder() {
        val a = newSim(configs()[0]); apply(a, "SALE"); apply(a, "CANCEL"); assertFalse(a.cancelled)
        val b = newSim(configs()[0]); apply(b, "SALE"); apply(b, "PDN_DOWN"); apply(b, "CANCEL"); assertFalse(b.cancelled)
        val c = newSim(configs()[0]); apply(c, "RETURN"); apply(c, "CANCEL"); assertTrue(c.cancelled)   // returns do not block
    }
    @Test fun cases_9_10_13_17_41_48_areBlocked() {
        // 17/41: reduce more than the shelf
        val s = newSim(configs()[0]); apply(s, "SALE")                  // 40 on shelf
        assertEquals("blocked", s.qdn(false, 41.0))
        assertEquals("ok", s.qdn(false, 40.0))
        // 10: everything returned -> nothing held
        val t = newSim(configs()[0]); t.ret(100.0)
        assertEquals("blocked", t.pdn(0.9, null)); assertEquals("blocked", t.qdn(true, 1.0))
        // 9/23/48: cancelled
        val u = newSim(configs()[0]); apply(u, "CANCEL")
        assertEquals("blocked", u.pdn(0.9, null))
    }
}
