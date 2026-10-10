package com.example.easy_billing.correction

import kotlin.math.min

/**
 * Pure FIFO slicing (Stage 3, Phase 3A). No Android / Room types, so it is
 * unit-testable on the JVM.
 *
 * Given the batches in FIFO order and a quantity to remove, it says exactly
 * how many units come from which batch (and therefore which purchase bill).
 * If the batches cannot cover the quantity, the leftover becomes ONE
 * UNALLOCATED slice (batchId = null) — it is never silently dropped and
 * never invented as coming from a bill.
 */
object FifoAllocator {

    const val EPS = 1e-9

    data class BatchView(
        val batchId: Int,
        val purchaseInvoiceId: Int?,
        val remaining: Double,
        val unitCostNet: Double,
        /** invoiceValue / quantityPurchased, or net cost when unknown. */
        val unitCostGross: Double
    )

    data class Slice(
        val batchId: Int?,
        val purchaseInvoiceId: Int?,
        val quantity: Double,
        val unitCostNet: Double,
        val unitCostGross: Double
    ) {
        /** True when this slice can be tied to a real purchase bill. */
        val attributable: Boolean get() = purchaseInvoiceId != null
    }

    data class Plan(val slices: List<Slice>, val consumed: Double, val shortfall: Double)

    fun plan(batches: List<BatchView>, quantity: Double, shortfallCostNet: Double = 0.0, shortfallCostGross: Double = 0.0): Plan {
        if (quantity <= 0.0) return Plan(emptyList(), 0.0, 0.0)
        var remaining = quantity
        var consumed = 0.0
        val out = ArrayList<Slice>()
        for (b in batches) {
            if (remaining <= EPS) break
            if (b.remaining <= 0.0) continue
            val take = min(b.remaining, remaining)
            out += Slice(b.batchId, b.purchaseInvoiceId, take, b.unitCostNet, b.unitCostGross)
            remaining -= take
            consumed += take
        }
        val shortfall = if (remaining > EPS) remaining else 0.0
        if (shortfall > 0.0) {
            out += Slice(null, null, shortfall, shortfallCostNet, shortfallCostGross)
        }
        return Plan(out, consumed, shortfall)
    }

    // ───────────── Stage 3B: giving units back ─────────────

    /** An allocation row as the restore logic sees it (newest first). */
    data class OutRow(
        val rowId: Int,
        val purchaseInvoiceId: Int?,
        val batchId: Int?,
        val quantity: Double,
        val quantityRestored: Double,
        val unitCostNet: Double,
        val unitCostGross: Double
    )

    data class Restore(val rowId: Int, val purchaseInvoiceId: Int?, val batchId: Int?, val quantity: Double, val unitCostNet: Double, val unitCostGross: Double)

    data class RestorePlan(val restores: List<Restore>, val leftover: Double)

    /**
     * Decides which allocation rows a returned quantity goes back to.
     * Rows are expected newest first, so the units sold last come back first
     * (the reverse of how they left). Whatever the rows cannot cover
     * (old sales with no rows, or a return larger than what is recorded)
     * is [RestorePlan.leftover] and restocks the way it always did.
     */
    fun planRestore(rows: List<OutRow>, quantity: Double): RestorePlan {
        if (quantity <= 0.0) return RestorePlan(emptyList(), 0.0)
        var remaining = quantity
        val out = ArrayList<Restore>()
        for (r in rows) {
            if (remaining <= EPS) break
            val room = r.quantity - r.quantityRestored
            if (room <= EPS) continue
            val take = min(room, remaining)
            out += Restore(r.rowId, r.purchaseInvoiceId, r.batchId, take, r.unitCostNet, r.unitCostGross)
            remaining -= take
        }
        return RestorePlan(out, if (remaining > EPS) remaining else 0.0)
    }
}
