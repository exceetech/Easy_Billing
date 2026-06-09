package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.GstPurchaseRecord
import com.example.easy_billing.db.Product
import com.example.easy_billing.db.Purchase
import com.example.easy_billing.db.PurchaseItem
import com.example.easy_billing.network.AddProductRequest
import com.example.easy_billing.network.RetrofitClient
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
        lines: List<PurchaseItemDraft>,
        importDetails: PurchaseImportDetailsDraft? = null
    ): SaveResult {
        val purchaseId = doSave(header, lines, importDetails)

        // Inline push so the user gets immediate feedback. The
        // SyncCoordinator is also kicked from PurchaseViewModel for
        // the rest of the pending queue.
        val outcome = runCatching { SyncManager(context).pushPurchaseImmediately(purchaseId) }
            .getOrElse { err ->
                SyncManager.SyncResult.Failed(err.message ?: err.javaClass.simpleName)
            }
        return SaveResult(purchaseId, outcome)
    }

    /**
     * Holds a product that already has a server_id and needs its
     * sales-tax / GSTR-1 fields pushed to the backend after the
     * local Room transaction commits.  Network I/O must NOT run
     * inside withTransaction.
     */
    private data class PendingProductUpdate(
        val serverId: Int,
        val line: PurchaseItemDraft
    )

    private suspend fun doSave(
        header: Purchase,
        lines: List<PurchaseItemDraft>,
        importDetails: PurchaseImportDetailsDraft?
    ): Int {
        // Collect products that need a backend field-update (already synced).
        val pendingUpdates = mutableListOf<PendingProductUpdate>()

        val purchaseId = db.withTransaction {

        require(lines.isNotEmpty()) { "Purchase must have at least one line" }

        val purchaseId = db.purchaseDao().insert(header).toInt()

        if (importDetails != null) {
            val importDetailsEntity = com.example.easy_billing.db.PurchaseImportDetails(
                purchaseId = purchaseId, // Will be replaced with serverId when synced if online
                localPurchaseId = purchaseId,
                portCode = importDetails.portCode,
                billOfEntryNumber = importDetails.billOfEntryNumber,
                billOfEntryDate = importDetails.billOfEntryDate,
                billOfEntryValue = importDetails.billOfEntryValue,
                documentType = importDetails.documentType,
                sezSupplierGstin = importDetails.sezSupplierGstin,
                syncStatus = "pending",
                deviceId = DeviceUtils.getDeviceId(context.applicationContext)
            )
            db.purchaseImportDetailsDao().insert(importDetailsEntity)
        }

        // 1. Credit Integration
        if (header.isCredit && header.creditAccountId != null) {
            val shopId = context.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                .getInt("SHOP_ID", 1)
            
            val account = db.creditAccountDao().getById(header.creditAccountId, shopId)
            if (account != null) {
                // Increase debt
                val newDue = account.dueAmount + header.invoiceValue
                db.creditAccountDao().updateDue(account.id, newDue, shopId)

                // Log transaction
                db.creditTransactionDao().insert(
                    com.example.easy_billing.db.CreditTransaction(
                        accountId = account.id,
                        shopId = shopId,
                        amount = header.invoiceValue,
                        type = "PURCHASE_CREDIT",
                        referenceInvoice = header.invoiceNumber,
                        isSynced = false
                    )
                )
                
                // Update the purchase record with the transaction id if needed
                // (Though we've already inserted it, we could update it now if we had the tx id)
                // Actually, the tx id is auto-generated. 
                // We'll just leave it linked via creditAccountId for now as per schema.
            }
        }

        lines.forEach { line ->
            // 1. Upsert (or force-insert) into shop_product using SALES tax
            //    from the line, marked as isPurchased=true so subsequent edit
            //    flows lock down stock-mutating fields. The selling price
            //    (user input) becomes the product price; if the user didn't
            //    set one we fall back to the cost price as a sensible default.
            //
            //    forceCreate=true skips the name+variant lookup so that a
            //    brand-new row is inserted even when an inactive product with
            //    the same key already exists (user chose "Create New" in the
            //    restore dialog).
            val newProductData = Product(
                name            = line.productName,
                variant         = line.variant,
                unit            = line.unit,
                price           = line.sellingPrice ?: line.costPrice,
                trackInventory  = true,
                isActive        = true,
                isPurchased     = true,
                hsnCode         = line.hsnCode,
                defaultGstRate  = (line.salesCgst + line.salesSgst)
                    .takeIf { it > 0 } ?: line.salesIgst,
                cgstPercentage  = line.salesCgst,
                sgstPercentage  = line.salesSgst,
                igstPercentage  = line.salesIgst,
                officialUqc     = line.officialUqc,
                hsnDescription  = line.hsnDescription,
                cessRate        = line.cessRate,
                supplyClassification = line.supplyClassification,
                category        = line.category
            )
            val productId = if (line.forceCreate) {
                db.productDao().insert(newProductData).toInt()
            } else {
                productRepo.upsert(newProductData)
            }

            // If this product already has a server_id, schedule a backend
            // field-update to run AFTER the transaction commits (network I/O
            // must not run inside withTransaction).
            db.productDao().getById(productId)?.serverId?.let { sid ->
                pendingUpdates.add(PendingProductUpdate(sid, line))
            }

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
                    salesIgstPercentage = line.salesIgst,

                    cessPercentage = line.cessPercentage,
                    cessAmount = line.cessAmount,
                    eligibilityForItc = line.eligibilityForItc,
                    availedItcIgst = line.availedItcIgst,
                    availedItcCgst = line.availedItcCgst,
                    availedItcSgst = line.availedItcSgst,
                    availedItcCess = line.availedItcCess,
                    hsnDescription = line.hsnDescription ?: "",
                    officialUqc = line.officialUqc ?: "",
                    supplyClassification = line.supplyClassification
                )
            )

            // 3. Inventory adjustment.
            //
            // Pass full batch metadata so the v21 purchase_batches
            // ledger carries the supplier / GST split. The user requested
            // to include GST in the unit cost for stock addition.
            val unitCostGross = if (line.quantity > 0.0) line.invoiceValue / line.quantity
                              else line.costPrice
            val combinedGst = (line.purchaseCgst + line.purchaseSgst)
                .takeIf { it > 0 } ?: line.purchaseIgst
            InventoryManager.addStock(
                db = db,
                productId = productId,
                quantity = line.quantity,
                costPrice = unitCostGross,
                batchMeta = InventoryManager.StockBatchMeta(
                    purchaseInvoiceId = purchaseId,
                    supplierName = header.supplierName,
                    supplierGstin = header.supplierGstin,
                    invoiceNumber = header.invoiceNumber,
                    batchCode = null,
                    unitCostExcludingTax = line.costPrice,
                    gstPercent = combinedGst,
                    cgstPercent = line.purchaseCgst,
                    sgstPercent = line.purchaseSgst,
                    igstPercent = line.purchaseIgst,
                    invoiceValue = line.invoiceValue,
                    taxableValue = line.taxableAmount
                )
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
                    invoiceDate       = header.invoiceDate ?: header.createdAt,
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
        } // end withTransaction

        // ── Post-transaction: push updated fields for already-synced products ──
        val token = context
            .getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
            .getString("TOKEN", null)
        if (token != null && pendingUpdates.isNotEmpty()) {
            for (upd in pendingUpdates) {
                val l = upd.line
                val combined = (l.salesCgst + l.salesSgst).takeIf { it > 0 } ?: l.salesIgst
                runCatching {
                    RetrofitClient.api.updateShopProduct(
                        token    = "Bearer $token",
                        serverId = upd.serverId,
                        request  = AddProductRequest(
                            name             = l.productName,
                            variant_name     = l.variant?.ifBlank { null },
                            unit             = l.unit ?: "piece",
                            price            = l.sellingPrice ?: l.costPrice,
                            track_inventory  = true,
                            initial_stock    = null,
                            cost_price       = null,
                            hsn_code         = l.hsnCode,
                            default_gst_rate = combined,
                            cgst_percentage  = l.salesCgst,
                            sgst_percentage  = l.salesSgst,
                            igst_percentage  = l.salesIgst,
                            official_uqc     = l.officialUqc,
                            hsn_description  = l.hsnDescription,
                            cess_rate        = l.cessRate,
                            supply_classification = l.supplyClassification,
                            category         = l.category,
                            is_purchased     = true
                        )
                    )
                } // fire-and-forget
            }
        }

        return purchaseId
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
        val salesIgst: Double = 0.0,

        // GSTR-1 product master fields
        val officialUqc: String? = null,
        val hsnDescription: String? = null,
        val cessRate: Double = 0.0,
        val supplyClassification: String = "TAXABLE",

        // Category (v40)
        val category: String = "",

        // GSTR-2 support fields
        val cessPercentage: Double = 0.0,
        val cessAmount: Double = 0.0,
        val eligibilityForItc: String = "Inputs",
        val availedItcIgst: Double = 0.0,
        val availedItcCgst: Double = 0.0,
        val availedItcSgst: Double = 0.0,
        val availedItcCess: Double = 0.0,

        /**
         * When true, bypass the name+variant upsert lookup and force-insert
         * a brand-new product row. Used when the user chooses "Create New"
         * on the purchase restore dialog (inactive product found).
         */
        val forceCreate: Boolean = false
    )

    data class PurchaseImportDetailsDraft(
        val portCode: String,
        val billOfEntryNumber: String,
        val billOfEntryDate: Long,
        val billOfEntryValue: Double,
        val documentType: String,
        val sezSupplierGstin: String? = null
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
