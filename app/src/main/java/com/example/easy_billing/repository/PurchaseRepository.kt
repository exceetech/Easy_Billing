package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.GstPurchaseRecord
import com.example.easy_billing.db.Product
import com.example.easy_billing.db.Purchase
import com.example.easy_billing.db.PurchaseItem
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.util.DeviceUtils
import androidx.room.withTransaction
import java.util.UUID

/**
 * Coordinates the full "save purchase" flow.
 *
 * Inserting a purchase invoice has four side-effects:
 *
 *   1. A row in `purchase_table`.
 *   2. One row per line in `purchase_items_table` (with both
 *      purchase-side and sales-side tax columns populated).
 *   3. An upsert into `shop_product` per line — the *sales* tax
 *      from the line is what gets persisted on the product, since
 *      that's what billing will charge later.
 *   4. An inventory adjustment for each line via the existing
 *      [InventoryManager.addStock] (which handles weighted-average
 *      cost + transaction logging in one place).
 *
 * Everything happens in a single Room transaction so a failure
 * leaves the DB consistent.
 */
class PurchaseRepository private constructor(
    private val context: android.content.Context,
    private val db: AppDatabase,
    private val productRepo: ProductRepository
) {

    /**
     * Outcome of [savePurchase] — bundles the new local id with the
     * result of the immediate backend push so the UI can render an
     * accurate toast (synced / offline-pending / push failed).
     */
    data class SaveResult(
        val purchaseId: Int,
        val syncOutcome: SyncManager.SyncResult
    )

    /**
     * Persist a purchase invoice and immediately attempt to push it
     * to the backend (if online). Returns both the local id and the
     * push outcome so callers can show a meaningful message.
     */
    suspend fun savePurchase(
        header: Purchase,
        lines: List<PurchaseItemDraft>
    ): SaveResult {
        val purchaseId = doSave(header, lines)

        // Inline push so the user gets immediate feedback. The
        // SyncCoordinator is also kicked from PurchaseViewModel for
        // the rest of the pending queue.
        val outcome = runCatching { SyncManager(context).pushPurchaseImmediately(purchaseId) }
            .getOrElse { err ->
                SyncManager.SyncResult.Failed(err.message ?: err.javaClass.simpleName)
            }
        return SaveResult(purchaseId, outcome)
    }

    private suspend fun doSave(
        header: Purchase,
        lines: List<PurchaseItemDraft>
    ): Int = db.withTransaction {

        require(lines.isNotEmpty()) { "Purchase must have at least one line" }

        val purchaseId = db.purchaseDao().insert(header).toInt()

        lines.forEach { line ->
            // 1. Upsert into shop_product using SALES tax from the
            //    line, marked as isPurchased=true so subsequent edit
            //    flows lock down stock-mutating fields. The selling
            //    price (user input) becomes the product price; if
            //    the user didn't set one we fall back to the cost
            //    price as a sensible default.
            val productId = productRepo.upsert(
                Product(
                    name           = line.productName,
                    variant        = line.variant,
                    unit           = line.unit,
                    price          = line.sellingPrice ?: line.costPrice,
                    trackInventory = true,
                    isActive       = true,
                    isPurchased    = true,
                    hsnCode        = line.hsnCode,
                    defaultGstRate = (line.salesCgst + line.salesSgst)
                        .takeIf { it > 0 } ?: line.salesIgst,
                    cgstPercentage = line.salesCgst,
                    sgstPercentage = line.salesSgst,
                    igstPercentage = line.salesIgst
                )
            )

            // 2. Insert into purchase_items_table with both tax sets.
            db.purchaseItemDao().insert(
                PurchaseItem(
                    purchaseId    = purchaseId,
                    productId     = productId,
                    productName   = line.productName,
                    variant       = line.variant,
                    hsnCode       = line.hsnCode,
                    quantity      = line.quantity,
                    unit          = line.unit,
                    taxableAmount = line.taxableAmount,
                    invoiceValue  = line.invoiceValue,
                    costPrice     = line.costPrice,

                    purchaseCgstPercentage = line.purchaseCgst,
                    purchaseSgstPercentage = line.purchaseSgst,
                    purchaseIgstPercentage = line.purchaseIgst,
                    purchaseCgstAmount     = line.taxableAmount * line.purchaseCgst / 100.0,
                    purchaseSgstAmount     = line.taxableAmount * line.purchaseSgst / 100.0,
                    purchaseIgstAmount     = line.taxableAmount * line.purchaseIgst / 100.0,

                    salesCgstPercentage = line.salesCgst,
                    salesSgstPercentage = line.salesSgst,
                    salesIgstPercentage = line.salesIgst
                )
            )

            // 3. Inventory adjustment.
            InventoryManager.addStock(
                db = db,
                productId = productId,
                quantity = line.quantity,
                costPrice = line.costPrice
            )

            // 4. GST register row (gst_purchase_records). One per
            //    line item — this is what feeds GSTR-2 / 3B reports
            //    and what gets pushed to the backend's
            //    `gst_purchase_record` table by SyncManager.syncGstPurchases().
            //
            // gstRate here is the *combined* purchase tax rate so
            // backend reports always have a single rate to summarise
            // by. The amount columns carry the actual splits.
            val combinedRate = (line.purchaseCgst + line.purchaseSgst)
                .takeIf { it > 0 } ?: line.purchaseIgst
            db.gstPurchaseRecordDao().insert(
                GstPurchaseRecord(
                    id                = UUID.randomUUID().toString(),
                    vendorGstin       = header.supplierGstin,
                    vendorName        = header.supplierName,
                    invoiceNumber     = header.invoiceNumber,
                    invoiceDate       = header.createdAt,
                    totalInvoiceValue = line.invoiceValue,
                    taxableValue      = line.taxableAmount,
                    gstRate           = combinedRate,
                    cgstAmount        = line.taxableAmount * line.purchaseCgst / 100.0,
                    sgstAmount        = line.taxableAmount * line.purchaseSgst / 100.0,
                    igstAmount        = line.taxableAmount * line.purchaseIgst / 100.0,
                    cessAmount        = 0.0,
                    hsnCode           = line.hsnCode.orEmpty(),
                    itcEligibility    = "Eligible",
                    expenseType       = "STOCK",
                    description       = listOfNotNull(
                        line.productName,
                        line.variant?.takeIf { it.isNotBlank() }?.let { "($it)" }
                    ).joinToString(" "),
                    syncStatus        = "pending",
                    deviceId          = DeviceUtils.getDeviceId(context.applicationContext),
                    createdAt         = System.currentTimeMillis(),
                    updatedAt         = System.currentTimeMillis()
                )
            )
        }

        purchaseId
    }

    suspend fun getRecent(limit: Int = 50) = db.purchaseDao().getRecent(limit)

    /**
     * Per-line input for [savePurchase]. Sales tax is what the user
     * sets while adding the product; purchase tax comes from the
     * supplier invoice.
     */
    data class PurchaseItemDraft(
        val productName: String,
        val variant: String? = null,
        val hsnCode: String? = null,
        val unit: String? = null,
        val quantity: Double,
        val taxableAmount: Double,
        val invoiceValue: Double,
        val costPrice: Double = if (quantity > 0) invoiceValue / quantity else 0.0,
        val sellingPrice: Double? = null,

        // Purchase tax (from supplier invoice)
        val purchaseCgst: Double = 0.0,
        val purchaseSgst: Double = 0.0,
        val purchaseIgst: Double = 0.0,

        // Sales tax (what we will charge customers)
        val salesCgst: Double = 0.0,
        val salesSgst: Double = 0.0,
        val salesIgst: Double = 0.0
    )

    companion object {
        @Volatile private var INSTANCE: PurchaseRepository? = null

        fun get(context: Context): PurchaseRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PurchaseRepository(
                    context = context.applicationContext,
                    db = AppDatabase.getDatabase(context),
                    productRepo = ProductRepository.get(context)
                ).also { INSTANCE = it }
            }
        }
    }
}
