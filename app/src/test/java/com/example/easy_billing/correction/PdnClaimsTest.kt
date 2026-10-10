package com.example.easy_billing.correction

import com.example.easy_billing.correction.PurchaseCorrectionMath.LineState
import com.example.easy_billing.correction.PurchaseCorrectionMath.Outcome
import com.example.easy_billing.correction.PurchaseCorrectionMath.PriceChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Stage 3C, pure JVM: T3C-1 .. T3C-8 numbers from the plan. */
class PdnClaimsTest {

    private val D = 1e-6
    private fun pool(id: Int, date: Long, gone: Double, claimed: Double, d: Double) =
        PdnClaims.Pool(id, id, date, gone, claimed, d)

    private fun ok(o: Outcome) = (o as Outcome.Ok).correction

    // T3C-1: 100 pens, 60 sold, 5 returned (refilled) BEFORE the PDN -> S=45, shelf 53.10, gone 64.90
    @Test fun t3c_1_returnBeforePdn() {
        val c = ok(PurchaseCorrectionMath.priceCorrection(
            LineState(held = 100.0, shelf = 45.0, stock = 45.0, averageCost = 11.80),
            PriceChange(10.0, 11.80, 9.0, 10.62)
        ))
        assertEquals(-53.10, c.totalShelfValueDelta, D)
        assertEquals(-64.90, c.totalVariance, D)
    }

    // T3C-2: 60 sold before the PDN, 5 returned after it -> restock at 10.62, 5.90 of gain reversed
    @Test fun t3c_2_returnAfterPdn() {
        val r = PdnClaims.plan(soldAt = 100L, restoreQty = 5.0, pools = listOf(pool(1, 200L, 60.0, 0.0, -1.18)))
        assertEquals(1, r.claims.size)
        assertEquals(5.0, r.claims[0].qty, D)
        assertEquals(5.90, r.varianceTotal, D)          // positive = loss: the gain is taken back
        assertEquals(10.62, 11.80 + r.costAdjustTotal / 5.0, D)
    }

    // T3C-3: sold AFTER the PDN -> nothing special
    @Test fun t3c_3_soldAfterPdn() {
        val r = PdnClaims.plan(soldAt = 300L, restoreQty = 5.0, pools = listOf(pool(1, 200L, 60.0, 0.0, -1.18)))
        assertTrue(r.claims.isEmpty())
    }

    // same timestamp counts as sale first
    @Test fun sameTimestampIsSaleFirst() {
        val r = PdnClaims.plan(soldAt = 200L, restoreQty = 2.0, pools = listOf(pool(1, 200L, 60.0, 0.0, -1.18)))
        assertEquals(2.0, r.claims.single().qty, D)
    }

    // T3C-4: cancelling the whole 60-pen sale -> 60 x 10.62 and a 70.80 claim
    @Test fun t3c_4_cancelWholeSale() {
        val r = PdnClaims.plan(100L, 60.0, listOf(pool(1, 200L, 60.0, 0.0, -1.18)))
        assertEquals(70.80, r.varianceTotal, D)
        assertEquals(637.20, 60 * 11.80 + r.costAdjustTotal, D)
    }

    // T3C-5 / T3C-7: claims never exceed the pool, even over repeated returns
    @Test fun t3c_7_neverClaimTwice() {
        var claimed = 0.0
        repeat(5) {
            val r = PdnClaims.plan(100L, 4.0, listOf(pool(1, 200L, 10.0, claimed, -1.18)))
            claimed += r.claims.sumOf { it.qty }
        }
        assertEquals(10.0, claimed, D)
    }

    // T3C-6: a price-UP correction reverses the signs
    @Test fun t3c_6_priceUp() {
        val r = PdnClaims.plan(100L, 5.0, listOf(pool(1, 200L, 60.0, 0.0, +1.18)))
        assertEquals(-5.90, r.varianceTotal, D)          // an earlier loss is taken back = gain
        assertEquals(5.90, r.costAdjustTotal, D)
    }

    // stacked corrections: only those dated after the sale count, each on its own
    @Test fun stackedOnlyLaterOnesCount() {
        val pools = listOf(pool(1, 150L, 20.0, 0.0, -1.0), pool(2, 250L, 20.0, 0.0, -0.5))
        val r = PdnClaims.plan(soldAt = 200L, restoreQty = 5.0, pools = pools)
        assertEquals(1, r.claims.size)
        assertEquals(2, r.claims[0].poolId)
        val both = PdnClaims.plan(soldAt = 100L, restoreQty = 5.0, pools = pools)
        assertEquals(2, both.claims.size)
        assertEquals(5.0 * 1.0 + 5.0 * 0.5, both.varianceTotal, D)
    }

    @Test fun randomNeverOverClaims() {
        val rnd = Random(3)
        repeat(3000) {
            val pools = (1..rnd.nextInt(4)).map { pool(it, rnd.nextInt(500).toLong(), rnd.nextInt(30).toDouble(), 0.0, rnd.nextDouble() - 0.5) }
            val soldAt = rnd.nextInt(500).toLong()
            val q = rnd.nextInt(40).toDouble()
            val r = PdnClaims.plan(soldAt, q, pools)
            r.claims.forEach { c ->
                val p = pools.first { it.poolId == c.poolId }
                assertTrue(c.qty <= q + D && c.qty <= p.goneQty + D && p.noteDate >= soldAt)
            }
        }
    }
}
