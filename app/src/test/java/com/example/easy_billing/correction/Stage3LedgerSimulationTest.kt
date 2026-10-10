package com.example.easy_billing.correction

import com.example.easy_billing.correction.PurchaseCorrectionMath.LineState
import com.example.easy_billing.correction.PurchaseCorrectionMath.Outcome
import com.example.easy_billing.correction.PurchaseCorrectionMath.PriceChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Stage 3 audit: a randomized model of the whole ledger (purchases, sales,
 * customer returns, price corrections) built from the REAL pure classes
 * (FifoAllocator, PdnClaims, PurchaseCorrectionMath).
 *
 * Accounting identity checked after EVERY step:
 *     money paid to suppliers = stock value + cost of goods sold (net of returns)
 *                               + booked gain/loss (note variance + claims)
 * and, once every sale has been returned, the booked gain/loss must be ~0
 * ("the same as if the correction had been made before the sale").
 */
class Stage3LedgerSimulationTest {

    private val EXTREME = false
    private var totalClamps = 0

    private class Batch(val inv: Int, var rem: Double, val created: Long, val id: Int)
    private class Inv(val id: Int, val qty: Double, var gross: Double, val created: Long)
    private class Alloc(val sale: Int, val inv: Int, val qty: Double, var restored: Double, val created: Long, val id: Int)
    private class Pool(val id: Int, val inv: Int, val date: Long, val gone: Double, val delta: Double, var claimed: Double = 0.0)
    private class Sale(val id: Int, val qty: Double, val frozen: Double, var returned: Double = 0.0)

    private class Model {
        var clock = 0L
        var nextId = 1
        val batches = ArrayList<Batch>()
        val invs = ArrayList<Inv>()
        val allocs = ArrayList<Alloc>()
        val pools = ArrayList<Pool>()
        val sales = ArrayList<Sale>()
        var stock = 0.0
        var avg = 0.0
        var paid = 0.0
        var cogsNet = 0.0
        var varianceNet = 0.0
        var ops = 0
        var clampExcess = 0.0     // value created when a negative restock cost is clamped to 0
        var clamps = 0
        val log = ArrayList<String>()

        fun buy(qty: Double, gross: Double) {
            val inv = Inv(nextId++, qty, gross, ++clock)
            invs += inv
            paid += qty * gross
            avg = if (stock <= 1e-9) gross else (stock * avg + qty * gross) / (stock + qty)
            stock += qty
            batches += Batch(inv.id, qty, inv.created, nextId++)
            log += "BUY inv${inv.id} qty=$qty gross=$gross | stock=$stock avg=$avg"
            ops++
        }

        fun sell(qty: Double) {
            if (qty > stock + 1e-9) return
            val frozen = avg
            val live = batches.filter { it.rem > 0 }.sortedWith(compareBy({ it.created }, { it.id }))
            val plan = FifoAllocator.plan(
                live.map { FifoAllocator.BatchView(it.id, it.inv, it.rem, 0.0, 0.0) }, qty
            )
            assertEquals(0.0, plan.shortfall, 1e-9)
            val sale = Sale(nextId++, qty, frozen)
            sales += sale
            val at = ++clock
            for (s in plan.slices) {
                batches.first { it.id == s.batchId }.rem -= s.quantity
                allocs += Alloc(sale.id, s.purchaseInvoiceId!!, s.quantity, 0.0, at, nextId++)
            }
            stock -= qty
            if (stock <= 1e-9) { stock = 0.0; avg = 0.0 }
            cogsNet += frozen * qty
            log += "SELL sale${sale.id} qty=$qty frozen=$frozen slices=${plan.slices.map { "inv${it.purchaseInvoiceId}:${it.quantity}" }} | stock=$stock avg=$avg"
            ops++
        }

        fun giveBack(sale: Sale, qty: Double) {
            val q = minOf(qty, sale.qty - sale.returned)
            if (q <= 1e-9) return
            val rows = allocs.filter { it.sale == sale.id }.sortedByDescending { it.id }
            val plan = FifoAllocator.planRestore(
                rows.map { FifoAllocator.OutRow(it.id, it.inv, null, it.qty, it.restored, 0.0, 0.0) }, q
            )
            assertEquals(0.0, plan.leftover, 1e-9)
            val camts = ArrayList<Double>()
            for (r in plan.restores) {
                val row = rows.first { it.id == r.rowId }
                val claims = PdnClaims.plan(
                    row.created, r.quantity,
                    pools.filter { it.inv == row.inv }.map { PdnClaims.Pool(it.id, it.id, it.date, it.gone, it.claimed, it.delta) }
                )
                for (c in claims.claims) {
                    pools.first { it.id == c.poolId }.claimed += c.qty
                    camts += c.amount
                }
                row.restored += r.quantity
                batches += Batch(row.inv, r.quantity, invs.first { it.id == row.inv }.created, nextId++)
            }
            // Fix C: cost cannot go below zero, so the excess comes off the loss claims.
            var excess = camts.sum() - sale.frozen * q
            if (excess > 1e-9) {
                for (i in camts.indices.reversed()) {
                    if (excess <= 1e-9) break
                    if (camts[i] <= 0.0) continue
                    val cut = minOf(camts[i], excess)
                    camts[i] = camts[i] - cut
                    excess -= cut
                }
            }
            varianceNet += camts.sum()
            val adj = -camts.sum()
            val raw = (sale.frozen * q + adj) / q
            if (raw < 0.0) { clampExcess += -raw * q; clamps++ }
            val cost = raw.coerceAtLeast(0.0)
            avg = if (stock <= 1e-9) cost else (stock * avg + q * cost) / (stock + q)
            stock += q
            sale.returned += q
            cogsNet -= sale.frozen * q
            log += "RETURN sale${sale.id} qty=$q restores=${plan.restores.map { "row${it.rowId}:${it.quantity}" }} adj=$adj cost=$cost | stock=$stock avg=$avg var=$varianceNet"
            ops++
        }

        fun pdn(inv: Inv, newGross: Double) {
            val shelf = batches.filter { it.inv == inv.id }.sumOf { it.rem }
            val line = LineState(held = inv.qty, shelf = shelf, stock = stock, averageCost = avg)
            val out = PurchaseCorrectionMath.priceCorrection(
                line, PriceChange(inv.gross / 1.18, inv.gross, newGross / 1.18, newGross)
            )
            val c = (out as? Outcome.Ok)?.correction ?: return
            paid += c.signedNoteValue
            avg = c.newAverage
            varianceNet += c.totalVariance
            val gone = inv.qty - shelf
            val delta = c.parts.sumOf { it.signedValue } / inv.qty
            if (gone > 1e-9 && abs(delta) > 1e-9) pools += Pool(nextId++, inv.id, ++clock, gone, delta)
            log += "PDN inv${inv.id} ${inv.gross}->$newGross H=${inv.qty} S=$shelf note=${c.signedNoteValue} var=${c.totalVariance} newAvg=${c.newAverage} gone=$gone | var=$varianceNet"
            inv.gross = newGross
            ops++
        }

        fun check(tag: String) {
            val tol = 0.05 + 0.02 * ops
            val lhs = paid
            val rhs = stock * avg + cogsNet + varianceNet - clampExcess
            assertTrue("$tag: paid=$lhs vs V+COGS+var=$rhs (ops=$ops)\n" + log.joinToString("\n"), abs(lhs - rhs) <= tol)
            assertEquals("$tag: shelf vs batches", stock, batches.sumOf { it.rem }, 1e-6)
            pools.forEach { assertTrue("$tag: over-claim", it.claimed <= it.gone + 1e-6) }
            allocs.forEach { assertTrue("$tag: over-restore", it.restored <= it.qty + 1e-9) }
        }
    }

    private fun rate(r: Random) = (500 + r.nextInt(1600)) / 100.0   // 5.00 .. 20.99
    private fun near(r: Random, g: Double) = Math.round(g * (0.75 + r.nextDouble() * 0.5) * 100.0) / 100.0 // within +-25%

    @Test fun randomSequencesKeepTheBooksBalanced() {
        val r = Random(20261009)
        repeat(1500) { run ->
            val m = Model()
            m.buy((20 + r.nextInt(80)).toDouble(), rate(r))
            repeat(12 + r.nextInt(25)) {
                when (r.nextInt(100)) {
                    in 0..14 -> m.buy((5 + r.nextInt(60)).toDouble(), rate(r))
                    in 15..49 -> if (m.stock >= 1) m.sell((1 + r.nextInt(minOf(30, m.stock.toInt()))).toDouble())
                    in 50..74 -> {
                        val open = m.sales.filter { it.qty - it.returned > 1e-9 }
                        if (open.isNotEmpty()) {
                            val s = open[r.nextInt(open.size)]
                            m.giveBack(s, (1 + r.nextInt((s.qty - s.returned).toInt().coerceAtLeast(1))).toDouble())
                        }
                    }
                    else -> m.invs[r.nextInt(m.invs.size)].let { m.pdn(it, if (EXTREME) rate(r) else near(r, it.gross)) }
                }
                m.check("run $run")
            }
        }
    }

    @Test fun returningEverythingLeavesNoLeftoverGainOrLoss() {
        val r = Random(77)
        repeat(1500) { run ->
            val m = Model()
            m.buy((30 + r.nextInt(70)).toDouble(), rate(r))
            if (r.nextBoolean()) m.buy((10 + r.nextInt(50)).toDouble(), rate(r))
            repeat(6 + r.nextInt(14)) {
                when (r.nextInt(100)) {
                    in 0..49 -> if (m.stock >= 1) m.sell((1 + r.nextInt(minOf(25, m.stock.toInt()))).toDouble())
                    in 50..64 -> {
                        val open = m.sales.filter { it.qty - it.returned > 1e-9 }
                        if (open.isNotEmpty()) {
                            val s = open[r.nextInt(open.size)]
                            m.giveBack(s, (1 + r.nextInt((s.qty - s.returned).toInt().coerceAtLeast(1))).toDouble())
                        }
                    }
                    else -> m.invs[r.nextInt(m.invs.size)].let { m.pdn(it, if (EXTREME) rate(r) else near(r, it.gross)) }
                }
            }
            // cancel every sale completely
            m.sales.forEach { m.giveBack(it, it.qty) }
            m.check("run $run end")
            totalClamps += m.clamps
            assertEquals("run $run: COGS", 0.0, m.cogsNet, 0.05 + 0.02 * m.ops)
            // With huge corrections the cost floor (fix C) cuts the claims, so the
            // "back to zero" property only applies to realistic rates.
            if (!EXTREME) assertEquals("run $run: leftover gain/loss", 0.0, m.varianceNet, 0.05 + 0.02 * m.ops)
            assertEquals("run $run: no value lost to the cost floor", 0.0, m.clampExcess, 1e-6)
            assertEquals("run $run: everything back on the shelf", m.invs.sumOf { it.qty }, m.stock, 1e-6)
        }
        println("CLAMPS in 1500 realistic runs: $totalClamps")
    }
}
