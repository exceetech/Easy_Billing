package com.example.easy_billing.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 tests T2-1..T2-3 (T2-4 push/pull round trip is a SyncManager
 * check done by hand: create a PRICE_CORRECTION note, sync, reinstall/pull).
 *
 * NEEDS schemas/.../68.json, which Room's KSP step writes on the first
 * build after the version bump to 68.
 */
@RunWith(AndroidJUnit4::class)
class PurchaseCorrectionDataLayerTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private lateinit var db: AppDatabase

    @Before fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    // T2-1: an old row survives 67 -> 68 and reads as a plain return.
    @Test fun t2_1_migration_keeps_old_rows() {
        val name = "migration-test"
        helper.createDatabase(name, 67).apply {
            execSQL(
                "INSERT INTO purchase_return_table (shop_id, productId, productName, quantityReturned, " +
                    "taxableAmount, invoiceValue, state, created_at, note_type, original_invoice_id) " +
                    "VALUES ('s', 5, 'Pen', 20, 200, 236, '', 1, 'D', 1)"
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(name, 68, true, AppDatabase.MIGRATION_67_68)
        migrated.query("SELECT note_kind, rate_delta_net, shelf_value_delta, quantityReturned FROM purchase_return_table").use {
            it.moveToFirst()
            assertNull(if (it.isNull(0)) null else it.getString(0))
            assertEquals(0.0, it.getDouble(1), 0.0)
            assertEquals(0.0, it.getDouble(2), 0.0)
            assertEquals(20.0, it.getDouble(3), 0.0)
        }
    }

    private fun note(type: String, qty: Double, kind: String?, value: Double = 0.0, net: Double = 0.0) =
        PurchaseReturn(
            productId = 5, productName = "Pen", quantityReturned = qty, taxableAmount = 0.0,
            invoiceValue = 0.0, noteType = type, noteKind = kind, originalInvoiceId = 1,
            noteRefundVoucherValue = value, rateDeltaNet = net
        )

    // T2-2: bill 100, D 20, C 5, QTY_CORRECTION (D) 10, a price note on top -> H = 75.
    @Test fun t2_2_held_pens() = runBlocking {
        val dao = db.purchaseReturnDao()
        dao.insert(note("D", 20.0, null))
        dao.insert(note("C", 5.0, null))
        dao.insert(note("D", 10.0, PurchaseNoteKind.QTY_CORRECTION))
        dao.insert(note("D", 80.0, PurchaseNoteKind.PRICE_CORRECTION, value = 94.40, net = -1.0))
        assertEquals(75.0, heldQuantity(100.0, dao.getNetQtyOut(1, 5)), 1e-9)
    }

    // T2-3: two price notes on one line add up to the corrected rate.
    @Test fun t2_3_cumulative_rate_difference() = runBlocking {
        val dao = db.purchaseReturnDao()
        dao.insert(note("D", 100.0, PurchaseNoteKind.PRICE_CORRECTION, value = 118.0, net = -1.0))
        dao.insert(note("C", 100.0, PurchaseNoteKind.PRICE_CORRECTION, value = 59.0, net = 0.5))
        assertEquals(-0.5, dao.getNetRateDeltaForLine(1, 5), 1e-9)
        assertEquals(-0.59, dao.getGrossRateDeltaForLine(1, 5), 1e-9)
        assertEquals(2, dao.getNotesForLine(1, 5).size)
    }
}
