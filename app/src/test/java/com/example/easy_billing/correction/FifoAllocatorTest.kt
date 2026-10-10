package com.example.easy_billing.correction

import com.example.easy_billing.correction.FifoAllocator.BatchView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 3A: FIFO slicing and the UNALLOCATED shortfall rule. Pure JVM. */
class FifoAllocatorTest {

    private fun b(id: Int, inv: Int?, rem: Double, net: Double = 10.0, gross: Double = 11.8) =
        BatchView(id, inv, rem, net, gross)

    @Test fun singleBatchTakesWhatIsAsked() {
        val p = FifoAllocator.plan(listOf(b(1, 100, 100.0)), 60.0)
        assertEquals(1, p.slices.size)
        assertEquals(60.0, p.slices[0].quantity, 1e-9)
        assertEquals(100, p.slices[0].purchaseInvoiceId)
        assertEquals(60.0, p.consumed, 1e-9)
        assertEquals(0.0, p.shortfall, 1e-9)
    }

    @Test fun spansTwoBatchesOldestFirst() {
        val p = FifoAllocator.plan(listOf(b(1, 100, 40.0), b(2, 200, 100.0)), 101.0)
        assertEquals(listOf(1, 2), p.slices.map { it.batchId })
        assertEquals(listOf(40.0, 61.0), p.slices.map { it.quantity })
        assertEquals(101.0, p.slices.sumOf { it.quantity }, 1e-9)
    }

    @Test fun shortLedgerBecomesOneUnallocatedSlice() {
        val p = FifoAllocator.plan(listOf(b(1, 100, 10.0)), 25.0, 7.0, 8.0)
        assertEquals(2, p.slices.size)
        val last = p.slices.last()
        assertNull(last.batchId); assertNull(last.purchaseInvoiceId)
        assertEquals(15.0, last.quantity, 1e-9)
        assertEquals(8.0, last.unitCostGross, 1e-9)
        assertEquals(15.0, p.shortfall, 1e-9)
        assertEquals(10.0, p.consumed, 1e-9)
        assertTrue(!last.attributable)
    }

    @Test fun noBatchesIsAllUnallocated() {
        val p = FifoAllocator.plan(emptyList(), 5.0)
        assertEquals(1, p.slices.size)
        assertEquals(5.0, p.shortfall, 1e-9)
    }

    @Test fun emptyAndZeroBatchesAreSkipped() {
        val p = FifoAllocator.plan(listOf(b(1, 100, 0.0), b(2, 200, 5.0)), 3.0)
        assertEquals(listOf(2), p.slices.map { it.batchId })
    }

    @Test fun nonPositiveQuantityGivesNothing() {
        assertTrue(FifoAllocator.plan(listOf(b(1, 1, 5.0)), 0.0).slices.isEmpty())
        assertTrue(FifoAllocator.plan(listOf(b(1, 1, 5.0)), -2.0).slices.isEmpty())
    }

    @Test fun driftBatchWithoutInvoiceIsNotAttributable() {
        val p = FifoAllocator.plan(listOf(b(9, null, 4.0)), 4.0)
        assertTrue(!p.slices[0].attributable)
        assertEquals(0.0, p.shortfall, 1e-9)
    }

    @Test fun slicesAlwaysSumToRequestedQuantity() {
        val r = java.util.Random(7)
        repeat(2000) {
            val n = r.nextInt(5)
            val batches = (1..n).map { b(it, it * 10, r.nextInt(50).toDouble()) }
            val q = r.nextInt(200) / 2.0
            val p = FifoAllocator.plan(batches, q)
            assertEquals(q, p.slices.sumOf { it.quantity }, 1e-9)
            batches.forEach { bv ->
                val used = p.slices.filter { it.batchId == bv.batchId }.sumOf { it.quantity }
                assertTrue(used <= bv.remaining + 1e-9)
            }
        }
    }

    // ── Stage 3B ──
    private fun row(id: Int, inv: Int?, q: Double, restored: Double = 0.0) =
        FifoAllocator.OutRow(id, inv, id, q, restored, 10.0, 11.8)

    @Test fun returnGoesBackNewestSliceFirst() {
        // rows newest first: bill B (61), bill A (40)
        val p = FifoAllocator.plan(listOf(b(1, 100, 40.0), b(2, 200, 100.0)), 101.0)
        assertEquals(2, p.slices.size)
        val plan = FifoAllocator.planRestore(listOf(row(2, 200, 61.0), row(1, 100, 40.0)), 70.0)
        assertEquals(listOf(2, 1), plan.restores.map { it.rowId })
        assertEquals(listOf(61.0, 9.0), plan.restores.map { it.quantity })
        assertEquals(0.0, plan.leftover, 1e-9)
    }

    @Test fun alreadyRestoredUnitsAreNotRestoredTwice() {
        val plan = FifoAllocator.planRestore(listOf(row(1, 100, 10.0, 4.0)), 10.0)
        assertEquals(6.0, plan.restores.single().quantity, 1e-9)
        assertEquals(4.0, plan.leftover, 1e-9)
    }

    @Test fun oldSaleWithNoRowsIsAllLeftover() {
        val plan = FifoAllocator.planRestore(emptyList(), 5.0)
        assertTrue(plan.restores.isEmpty())
        assertEquals(5.0, plan.leftover, 1e-9)
    }

    @Test fun restoresNeverExceedAskedOrRecorded() {
        val r = java.util.Random(11)
        repeat(2000) {
            val rows = (1..r.nextInt(5)).map { row(it, it * 10, r.nextInt(30).toDouble(), 0.0).let { x -> x.copy(quantityRestored = (x.quantity * r.nextDouble()).let { v -> kotlin.math.floor(v) }) } }
            val q = r.nextInt(100).toDouble()
            val plan = FifoAllocator.planRestore(rows, q)
            assertEquals(q, plan.restores.sumOf { it.quantity } + plan.leftover, 1e-9)
            plan.restores.forEach { rs ->
                val src = rows.first { it.rowId == rs.rowId }
                assertTrue(rs.quantity <= src.quantity - src.quantityRestored + 1e-9)
            }
        }
    }
}
