package com.example.easy_billing.correction

import kotlin.math.min

/**
 * Stage 3C, pure maths: when pens that were sold BEFORE a price correction
 * come back from a customer, how much of that correction do they take back?
 *
 * No Android types, so it is unit-tested on the JVM.
 */
object PdnClaims {

    private const val EPS = 1e-9

    /** A price correction's write-off pool, with what has already been claimed. */
    data class Pool(
        val poolId: Int,
        val noteId: Int,
        val noteDate: Long,
        val goneQty: Double,
        val claimedQty: Double,
        /** signed gross change per pen, new − old */
        val deltaGross: Double
    )

    data class Claim(
        val poolId: Int,
        val noteId: Int,
        val qty: Double,
        /** variance sign: positive = loss, negative = gain */
        val amount: Double,
        /** signed change in the per-pen cost times qty (what the restock cost moves by) */
        val costAdjust: Double
    )

    data class Result(val claims: List<Claim>) {
        val costAdjustTotal: Double get() = claims.sumOf { it.costAdjust }
        val varianceTotal: Double get() = claims.sumOf { it.amount }
    }

    /**
     * @param soldAt     when these pens left the shelf (allocation time)
     * @param restoreQty how many come back now
     * A note counts only if it is dated on or after [soldAt]
     * ("same timestamp = sale first"). Each note is claimed independently, so
     * stacked corrections each take back their own part.
     */
    fun plan(soldAt: Long, restoreQty: Double, pools: List<Pool>): Result {
        if (restoreQty <= EPS) return Result(emptyList())
        val out = ArrayList<Claim>()
        for (p in pools.sortedWith(compareBy({ it.noteDate }, { it.poolId }))) {
            if (p.noteDate < soldAt) continue
            val room = p.goneQty - p.claimedQty
            if (room <= EPS) continue
            val c = min(restoreQty, room)
            if (c <= EPS) continue
            out += Claim(p.poolId, p.noteId, c, -c * p.deltaGross, c * p.deltaGross)
        }
        return Result(out)
    }
}
