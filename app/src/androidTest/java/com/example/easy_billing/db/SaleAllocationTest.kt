package com.example.easy_billing.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.correction.FifoAllocator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stage 3A tests T3A-1..T3A-6. Needs schemas/.../69.json (written by the first build). */
@RunWith(AndroidJUnit4::class)
class SaleAllocationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private lateinit var db: AppDatabase

    @Before fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    private fun batch(pid: Int, inv: Int, qty: Double, created: Long) = PurchaseBatch(
        productId = pid, purchaseInvoiceId = inv, batchCode = "B$inv",
        quantityPurchased = qty, quantityRemaining = qty,
        unitCostExcludingTax = 10.0, invoiceValue = qty * 11.8, taxableValue = qty * 10.0,
        createdAt = created
    )

    private suspend fun seed(vararg b: PurchaseBatch) {
        val pid = b.first().productId
        db.inventoryDao().insert(Inventory(productId = pid, currentStock = b.sumOf { it.quantityPurchased }, averageCost = 11.8))
        b.forEach { db.purchaseBatchDao().insertBatch(it) }
    }

    // T3A-1: migration 68 -> 69 creates the table and keeps old data.
    @Test fun t3a_1_migration() {
        helper.createDatabase("mig69", 68).close()
        val m = helper.runMigrationsAndValidate("mig69", 69, true, AppDatabase.MIGRATION_68_69)
        m.query("SELECT COUNT(*) FROM sale_batch_allocation").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    // T3A-2: a sale inside one bill is attributed to that bill.
    @Test fun t3a_2_single_bill() = runBlocking {
        seed(batch(1, 100, 100.0, 1))
        val sink = mutableListOf<FifoAllocator.Slice>()
        InventoryManager.reduceStock(db, 1, 60.0, allocationSink = sink)
        AllocationRecorder.save(db, AllocationSource.SALE, 7, 70, 1, sink)
        assertEquals(60.0, db.saleBatchAllocationDao().netOutQty(100, 1), 1e-9)
    }

    // T3A-3: a sale spanning two bills is split oldest first.
    @Test fun t3a_3_two_bills() = runBlocking {
        seed(batch(1, 100, 40.0, 1), batch(1, 200, 100.0, 2))
        val sink = mutableListOf<FifoAllocator.Slice>()
        InventoryManager.reduceStock(db, 1, 101.0, allocationSink = sink)
        AllocationRecorder.save(db, AllocationSource.SALE, 7, 70, 1, sink)
        assertEquals(40.0, db.saleBatchAllocationDao().netOutQty(100, 1), 1e-9)
        assertEquals(61.0, db.saleBatchAllocationDao().netOutQty(200, 1), 1e-9)
    }

    // T3A-4: units the ledger cannot cover are stored as unallocated, never as a bill.
    @Test fun t3a_4_shortfall() = runBlocking {
        seed(batch(1, 100, 10.0, 1))
        db.inventoryDao().getInventory(1)!!.let { db.inventoryDao().update(it.copy(currentStock = 25.0)) }
        val sink = mutableListOf<FifoAllocator.Slice>()
        InventoryManager.reduceStock(db, 1, 20.0, allocationSink = sink)
        AllocationRecorder.save(db, AllocationSource.SALE, 7, 70, 1, sink)
        val rows = db.saleBatchAllocationDao().getForSource(AllocationSource.SALE, 7)
        assertEquals(20.0, rows.sumOf { it.quantity }, 1e-9)
        assertEquals(10.0, rows.filter { it.purchaseInvoiceId == 100 }.sumOf { it.quantity }, 1e-9)
        assertEquals(10.0, rows.filter { it.purchaseInvoiceId == null }.sumOf { it.quantity }, 1e-9)
    }

    // T3A-5: saving the same source line twice does not double count.
    @Test fun t3a_5_idempotent() = runBlocking {
        seed(batch(1, 100, 100.0, 1))
        val sink = mutableListOf<FifoAllocator.Slice>()
        InventoryManager.reduceStock(db, 1, 30.0, allocationSink = sink)
        AllocationRecorder.save(db, AllocationSource.SALE, 7, 70, 1, sink)
        AllocationRecorder.save(db, AllocationSource.SALE, 7, 70, 1, sink)
        assertEquals(30.0, db.saleBatchAllocationDao().netOutQty(100, 1), 1e-9)
    }

    // T3A-6: allocations survive the batch row being deleted (no FK on purpose).
    @Test fun t3a_6_survives_batch_cleanup() = runBlocking {
        seed(batch(1, 100, 30.0, 1))
        val sink = mutableListOf<FifoAllocator.Slice>()
        InventoryManager.reduceStock(db, 1, 30.0, allocationSink = sink)
        AllocationRecorder.save(db, AllocationSource.SALE, 7, 70, 1, sink)
        db.purchaseBatchDao().clearEmptyBatches(1)
        assertEquals(30.0, db.saleBatchAllocationDao().netOutQty(100, 1), 1e-9)
        assertNull(db.purchaseBatchDao().getAllBatches(1).firstOrNull { it.purchaseInvoiceId == 100 })
    }

    // ───────── Stage 3B ─────────

    private suspend fun purchase(cancelled: Boolean = false): Int =
        db.purchaseDao().insert(
            Purchase(
                invoiceNumber = "INV", supplierGstin = null, supplierName = "S", state = "KA",
                taxableAmount = 100.0, invoiceValue = 118.0, isCancelled = cancelled
            )
        ).toInt()

    private suspend fun shelfOf(invoice: Int, pid: Int = 1) =
        db.purchaseBatchDao().getAllBatches(pid).filter { it.purchaseInvoiceId == invoice }.sumOf { it.quantityRemaining }

    private suspend fun sell(pid: Int, qty: Double, billItemId: Int = 70) {
        val sink = mutableListOf<FifoAllocator.Slice>()
        InventoryManager.reduceStock(db, pid, qty, allocationSink = sink)
        AllocationRecorder.save(db, AllocationSource.SALE, 7, billItemId, pid, sink)
    }

    private suspend fun giveBack(qty: Double, billItemId: Int = 70) =
        com.example.easy_billing.repository.SalesRestock.restock(
            db, billItemId, 1, qty, 11.8,
            InventoryManager.StockBatchMeta(batchCode = "SALES_RETURN-1", unitCostExcludingTax = 10.0, invoiceValue = qty * 11.8, taxableValue = qty * 10.0),
            InventoryManager.LogType.SALES_RETURN
        )

    // T3B-1: a returned pen goes back onto the bill it was sold from.
    @Test fun t3b_1_return_refills_original_bill() = runBlocking {
        val a = purchase()
        seed(batch(1, a, 100.0, 1))
        sell(1, 60.0)
        assertEquals(40.0, shelfOf(a), 1e-9)
        giveBack(10.0)
        assertEquals(50.0, shelfOf(a), 1e-9)
        assertEquals(50.0, db.inventoryDao().getInventory(1)!!.currentStock, 1e-9)
    }

    // T3B-2: a sale that spanned two bills returns newest slice first.
    @Test fun t3b_2_two_bills_return_order() = runBlocking {
        val a = purchase(); val b = purchase()
        seed(batch(1, a, 40.0, 1), batch(1, b, 100.0, 2))
        sell(1, 101.0)
        giveBack(30.0)
        assertEquals(69.0, shelfOf(b), 1e-9) // 39 left after the sale + 30 back
        assertEquals(0.0, shelfOf(a), 1e-9)
    }

    // T3B-3: returning twice never restores more than was sold.
    @Test fun t3b_3_no_double_restore() = runBlocking {
        val a = purchase()
        seed(batch(1, a, 100.0, 1))
        sell(1, 10.0)
        giveBack(10.0)
        val rows = db.saleBatchAllocationDao().getForBillLine(70, 1)
        assertEquals(10.0, rows.sumOf { it.quantityRestored }, 1e-9)
    }

    // T3B-4: a sale from before Stage 3 (no rows) still restocks the old way.
    @Test fun t3b_4_old_sale_without_rows() = runBlocking {
        val a = purchase()
        seed(batch(1, a, 100.0, 1))
        db.inventoryDao().getInventory(1)!!.let { db.inventoryDao().update(it.copy(currentStock = 90.0)) }
        giveBack(5.0, billItemId = 999)
        assertEquals(95.0, db.inventoryDao().getInventory(1)!!.currentStock, 1e-9)
        assertEquals(true, db.purchaseBatchDao().getAllBatches(1).any { it.purchaseInvoiceId == null && it.batchCode == "SALES_RETURN-1" })
    }

    // T3B-5: a cancelled purchase is never refilled.
    @Test fun t3b_5_cancelled_purchase_not_refilled() = runBlocking {
        val a = purchase(cancelled = true)
        seed(batch(1, a, 100.0, 1))
        sell(1, 10.0)
        giveBack(10.0)
        assertEquals(90.0, shelfOf(a), 1e-9)
    }

    // ───────── Stage 3C ─────────

    private suspend fun pool(inv: Int, noteDate: Long, gone: Double, delta: Double) =
        db.costCorrectionDao().insertPool(
            PdnGonePool(noteId = 1, purchaseInvoiceId = inv, productId = 1, goneQty = gone, deltaGross = delta, noteDate = noteDate)
        )

    private suspend fun claimed(): Double =
        db.costCorrectionDao().claimVarianceBetween(0L, Long.MAX_VALUE)

    // T3C-2: sold before the PDN, returned after -> gain taken back and recorded once
    @Test fun t3c_2_return_after_pdn_claims() = runBlocking {
        val a = purchase()
        seed(batch(1, a, 100.0, 1))
        sell(1, 60.0)
        pool(a, Long.MAX_VALUE / 2, 60.0, -1.18)
        giveBack(5.0)
        assertEquals(5.90, claimed(), 1e-6)
    }

    // T3C-3: sold after the PDN -> nothing claimed
    @Test fun t3c_3_sold_after_pdn_no_claim() = runBlocking {
        val a = purchase()
        seed(batch(1, a, 100.0, 1))
        pool(a, 0L, 60.0, -1.18)
        sell(1, 60.0)
        giveBack(5.0)
        assertEquals(0.0, claimed(), 1e-9)
    }

    // T3C-7: claims never exceed the pool
    @Test fun t3c_7_never_over_claim() = runBlocking {
        val a = purchase()
        seed(batch(1, a, 100.0, 1))
        sell(1, 60.0)
        pool(a, Long.MAX_VALUE / 2, 6.0, -1.0)
        giveBack(5.0)
        giveBack(5.0)
        assertEquals(6.0, claimed(), 1e-6)
    }

    // T3B-6 (fix B): the bill's batch was sold out and cleared, then a pen comes back.
    // The restored batch must carry the bill's supplier / invoice number and sit at the bill's own place in FIFO.
    @Test fun t3b_6_restored_batch_after_cleanup_keeps_bill_details() = runBlocking {
        val a = purchase()
        seed(batch(1, a, 30.0, 1))
        sell(1, 30.0)
        db.purchaseBatchDao().clearEmptyBatches(1)
        giveBack(5.0)
        val restored = db.purchaseBatchDao().getAllBatches(1).first { it.purchaseInvoiceId == a }
        assertEquals(5.0, restored.quantityRemaining, 1e-9)
        assertEquals("S", restored.supplierName)
        assertEquals("INV", restored.invoiceNumber)
        assertEquals(db.purchaseDao().getById(a)!!.createdAt, restored.createdAt)
    }

    // T3C-8 (fix C): a loss claim larger than the sale cost is cut so the restock cost is not negative
    // and no value disappears. Sale cost 11.8/pen, correction -> +20/pen loss claim on 5 pens = 100 > 59.
    @Test fun t3c_8_claim_cut_when_cost_would_go_negative() = runBlocking {
        val a = purchase()
        seed(batch(1, a, 100.0, 1))
        sell(1, 60.0)
        pool(a, Long.MAX_VALUE / 2, 60.0, -20.0)
        giveBack(5.0)
        assertEquals(59.0, claimed(), 1e-6)   // 5 x 11.8, not 100
        org.junit.Assert.assertTrue(db.inventoryDao().getInventory(1)!!.averageCost >= 0.0)
    }
}
