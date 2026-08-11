package com.example.easy_billing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.easy_billing.db.*
import com.example.easy_billing.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CancelCancelBugTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testCancelBug() = runBlocking {
        // Setup Product
        val pid = db.productDao().insert(Product(name = "Test", isPurchased = true, costPrice = 25.0, sellingPrice = 30.0).apply { id = 1 }).toInt()

        // Setup Purchase
        val purchaseId = db.purchaseDao().insert(Purchase(supplierName = "Supplier", invoiceNumber = "INV1")).toInt()
        db.purchaseItemDao().insert(PurchaseItem(purchaseId = purchaseId, productId = pid, productName = "Test", quantity = 1000.0, costPrice = 25.0, invoiceValue = 25000.0, taxableAmount = 25000.0))

        // Manually record batch (as InventoryManager would)
        db.purchaseBatchDao().insertBatch(PurchaseBatch(productId = pid, purchaseInvoiceId = purchaseId, quantityPurchased = 1000.0, quantityRemaining = 1000.0, unitCostExcludingTax = 25.0, isSynced = false))

        // Sale of 10
        val batch1 = db.purchaseBatchDao().getAllBatches(pid).first()
        db.purchaseBatchDao().updateBatch(batch1.copy(quantityRemaining = 990.0))

        // Sales Return of 5
        db.purchaseBatchDao().insertBatch(PurchaseBatch(productId = pid, purchaseInvoiceId = null, quantityPurchased = 5.0, quantityRemaining = 5.0, unitCostExcludingTax = 25.0, isSynced = false))

        // Purchase Credit (Credit Note) of 10
        db.purchaseBatchDao().insertBatch(PurchaseBatch(productId = pid, purchaseInvoiceId = purchaseId, quantityPurchased = 10.0, quantityRemaining = 10.0, unitCostExcludingTax = 25.0, isSynced = false))
        db.purchaseReturnDao().insert(PurchaseReturn(originalInvoiceId = purchaseId, productId = pid, quantityReturned = 10.0, noteType = "C"))

        // Check canCancel
        val items = db.purchaseItemDao().getByPurchase(purchaseId)
        var soldUnits = 0.0
        var remainingValue = 0.0

        for (p in items.mapNotNull { it.productId }.distinct()) {
            val batches = db.purchaseBatchDao().getAllBatches(p).filter { it.purchaseInvoiceId == purchaseId }
            val purchased = batches.sumOf { it.quantityPurchased }
            val onHand = batches.sumOf { it.quantityRemaining }
            val consumed = purchased - onHand
            val returned = db.purchaseReturnDao().getTotalReturnedForInvoiceProduct(purchaseId, p).coerceAtLeast(0.0)
            val unexplained = consumed - returned
            if (unexplained > 0.01) soldUnits += unexplained
            remainingValue += batches.sumOf { it.quantityRemaining * it.unitCostExcludingTax }
        }

        assertTrue("soldUnits is $soldUnits. Expected > 0.01", soldUnits > 0.01)
    }
}
