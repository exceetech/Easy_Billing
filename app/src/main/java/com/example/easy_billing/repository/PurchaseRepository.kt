package com.example.easy_billing.repository

import com.example.easy_billing.util.appNow

import android.content.Context
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.db.AppDatabase
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
        val productId: Int,
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

        // A credit purchase with no account is contradictory: the credit block
        // below would be skipped, so the amount would raise nobody's due and
        // leave no CreditTransaction — silently under-reporting payables.
        // Store it as a cash purchase rather than persisting that state.
        val safeHeader =
            if (header.isCredit && header.creditAccountId == null) {
                header.copy(isCredit = false, creditAccountId = null)
            } else if (!header.isCredit && header.creditAccountId != null) {
                // Likewise, a cash purchase shouldn't carry an account id.
                header.copy(creditAccountId = null)
            } else header

        val purchaseId = db.withTransaction {

        require(lines.isNotEmpty()) { "Purchase must have at least one line" }

        val purchaseId = db.purchaseDao().insert(safeHeader).toInt()

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
        if (safeHeader.isCredit && safeHeader.creditAccountId != null) {
            // Refuse rather than half-complete. Defaulting to 1 wrote the debt
            // into shop 1's books; defaulting to -1 alone would be worse — the
            // account lookup below returns null, the `if (account != null)`
            // skips silently, and the purchase saves as credit with no debt
            // recorded against anyone. Throwing here rolls back the whole
            // withTransaction block, so the purchase fails visibly instead.
            val shopId = context.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                .getInt("SHOP_ID", -1)

            check(shopId > 0) {
                "No shop selected — refusing to save a credit purchase whose debt can't be recorded"
            }


            val account = db.creditAccountDao().getById(safeHeader.creditAccountId, shopId)
            if (account != null) {
                // Increase debt
                // Adjustment in SQL rather than a total computed from a read —
                // see CreditAccountDao.addToDue.
                db.creditAccountDao().addToDue(account.id, safeHeader.invoiceValue, shopId)

                // Log transaction
                db.creditTransactionDao().insert(
                    com.example.easy_billing.db.CreditTransaction(
                        accountId = account.id,
                        shopId = shopId,
                        amount = safeHeader.invoiceValue,
                        type = "PURCHASE_CREDIT",
                        referenceInvoice = safeHeader.invoiceNumber,
                        // Ties the payable to this purchase so a later return,
                        // debit note or cancellation can find how much of it is
                        // still owed. sourceDoc marks it as the original buy.
                        purchaseId = purchaseId,
                        sourceDoc = "PBUY:$purchaseId",
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
                isTaxInclusive  = line.isTaxInclusive,
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
            //
            // Report 5 fix: also stamp pending_field_sync = 1 now, in the
            // same transaction as the local write. This mirrors the fix
            // already applied to ProductRepository.updateSalesFieldsOnly —
            // the post-transaction push below is fire-and-forget, and
            // without this flag a failed push here left the backend with
            // stale price/GST data forever, with no retry. Setting it here
            // means SyncManager.syncProductFieldEdits() will pick this
            // product up and retry if the push below doesn't succeed.
            db.productDao().getById(productId)?.serverId?.let { sid ->
                pendingUpdates.add(PendingProductUpdate(productId, sid, line))
                db.productDao().markFieldPending(productId)
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
                    discountAmount = line.discountAmount,
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
            // Pass full batch metadata so the v21 purchase_batches ledger
            // carries the supplier / GST split. addStock's costPrice keeps the
            // GST-inclusive (gross) per-unit value used by the inventory log, but
            // the batch's unitCostExcludingTax must be the NET cost
            // (taxableAmount / quantity). That column is "excluding tax" and
            // drives the weighted-average COGS via recomputeAvgFromBatches, so
            // storing the gross value there baked tax into valuation.
            val unitCostGross = if (line.quantity > 0.0) line.invoiceValue / line.quantity
                              else line.costPrice
            val unitCostNet = if (line.quantity > 0.0) line.taxableAmount / line.quantity
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
                    supplierName = safeHeader.supplierName,
                    supplierGstin = safeHeader.supplierGstin,
                    invoiceNumber = safeHeader.invoiceNumber,
                    batchCode = null,
                    unitCostExcludingTax = unitCostNet,
                    gstPercent = combinedGst,
                    cgstPercent = line.purchaseCgst,
                    sgstPercent = line.purchaseSgst,
                    igstPercent = line.purchaseIgst,
                    invoiceValue = line.invoiceValue,
                    taxableValue = line.taxableAmount
                )
            )

            // 4. (Removed) The per-line GST purchase-register row that fed the
            //    gst_purchase_records table. That table was retired — it was
            //    never read on the device and its server reports (GSTR-3B/email)
            //    are unused. The authoritative GST purchase data still lives in
            //    purchase_table / purchase_items.
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
                            is_purchased     = true,
                            is_tax_inclusive = l.isTaxInclusive
                        )
                    )
                }.onSuccess {
                    // Report 5 fix: clear the pending flag only on confirmed
                    // success. A failure leaves it set, so SyncManager
                    // retries this product on the next background sync.
                    db.productDao().markFieldSynced(upd.productId)
                }
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
        val discountAmount: Double = 0.0,
        val invoiceValue: Double,
        val costPrice: Double = if (quantity > 0) invoiceValue / quantity else 0.0,
        val sellingPrice: Double? = null,
        val isTaxInclusive: Boolean = false,

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
