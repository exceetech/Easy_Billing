package com.example.easy_billing.sync

import android.content.Context
import androidx.room.withTransaction
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.InventoryValuation
import com.example.easy_billing.db.BillingSettings
import com.example.easy_billing.db.CreditAccount
import com.example.easy_billing.db.Inventory
import com.example.easy_billing.db.StoreInfo
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.CreateBillRequest
import com.example.easy_billing.network.CancelBillRequest
import com.example.easy_billing.util.GstEngine
import kotlinx.coroutines.sync.withLock
import com.example.easy_billing.network.BillItemRequest
import com.example.easy_billing.network.CreateCreditAccountRequest
import com.example.easy_billing.network.CreditSyncRequest
import com.example.easy_billing.network.InventoryLogRequest
import com.example.easy_billing.network.GstProfileRequest
import com.example.easy_billing.network.GstSalesSyncRequest
import com.example.easy_billing.network.GstPurchaseSyncRequest
import com.example.easy_billing.network.GstSaleRecordDto
import com.example.easy_billing.network.GstPurchaseRecordDto
import com.example.easy_billing.network.CreateGstSalesInvoiceDto
import com.example.easy_billing.network.CreateGstSalesItemDto
import com.example.easy_billing.network.GstSalesSyncBatchRequest
import com.example.easy_billing.network.AddProductRequest
import com.example.easy_billing.network.GlobalProductRegisterRequest
import com.example.easy_billing.network.GstSalesCancelRequest
import com.example.easy_billing.network.PurchaseDto
import com.example.easy_billing.network.PurchaseItemDto
import com.example.easy_billing.network.PurchaseReturnDto
import com.example.easy_billing.network.PurchaseReturnSyncRequest
import com.example.easy_billing.network.PurchaseSyncRequest
import com.example.easy_billing.network.ScrapDto
import com.example.easy_billing.network.ScrapSyncRequest
import com.example.easy_billing.network.CreditNoteDto
import com.example.easy_billing.network.CreditNoteItemDto
import com.example.easy_billing.network.CreditNoteSyncRequest
import com.example.easy_billing.network.InventoryLogResponse
import com.example.easy_billing.network.PurchaseResponse
import com.example.easy_billing.api.PurchaseImportDetailsSyncRequest
import com.example.easy_billing.api.PurchaseImportDetailsDto
import android.util.Log

import com.example.easy_billing.network.PurchaseBatchDto
import com.example.easy_billing.network.PurchaseBatchSyncRequest
import com.example.easy_billing.network.CreditNoteResponseDto
import com.example.easy_billing.network.PurchaseReturnResponseDto
import com.example.easy_billing.network.PurchaseBatchResponseDto
import com.example.easy_billing.network.CategoryDto
import com.example.easy_billing.network.CategorySyncRequest
import com.example.easy_billing.network.CustomerDto
import com.example.easy_billing.network.CustomerSyncRequest

class SyncManager(private val context: Context) {


    suspend fun syncAll() {

        // 🔥 ORDER MATTERS — products before anything that references
        // them (purchases, bills, returns, scrap), pulls last.

        syncAccounts()           // account first
        syncCredit()             // then transactions
        syncCategories()         // custom categories (string-on-product, no FK)
        syncShopProducts()       // push unsynced shop_product (and global)
        syncCustomers()          // customer master (after products, before invoices)
        syncInventory()          // inventory BEFORE bills
        syncBills()              // then bills
        syncPurchases()
        syncPurchaseBatches()
        syncPurchaseImportDetails()
        syncPurchaseBatches()
        syncImportServices()     // push import services
        syncPurchaseReturns()    // push purchase returns (debit notes)
        syncCreditNotes()        // push sales returns (credit notes)
        syncScrapEntries()       // push scrap
        syncGstProfile()
        syncGstSales()
        syncGstPurchases()
        syncGstInvoices()        // push GST-aware invoice batch
        syncGstCancellations()   // push pending GST invoice cancellations
        syncBillCancellations()  // push voided bills to the analytics table
        syncStoreInfo()          // pull latest
        syncBillingSettings()    // pull settings
    }

    /* ==================================================================
     *  Push-side sync — local rows → backend
     * ================================================================== */

    /**
     * Pushes locally-created products that haven't been synced yet
     * (those with `serverId == null`). For each row we:
     *
     *   1. Call `addProductToShop` → backend assigns a `product_id`.
     *   2. Persist that id back to the local row.
     *   3. Call `registerGlobalProduct` so the global catalogue
     *      always receives the name/HSN/variant — independent of
     *      whether the shop endpoint promotes them server-side.
     */
    suspend fun syncShopProducts() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: run {
                Log.w(SYNC_TAG, "syncShopProducts skipped — no auth token")
                return
            }
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val pending = db.productDao().getUnsynced()
        Log.d(SYNC_TAG, "syncShopProducts: ${pending.size} unsynced product(s)")
        for (product in pending) {
            try {
                val response = api.addProductToShop(
                    token,
                    AddProductRequest(
                        name             = product.name,
                        variant_name     = product.variant,
                        unit             = product.unit ?: "piece",
                        price            = product.price,
                        track_inventory  = product.trackInventory,
                        initial_stock    = null,
                        cost_price       = null,
                        hsn_code         = product.hsnCode,
                        default_gst_rate = product.defaultGstRate,
                        cgst_percentage  = product.cgstPercentage,
                        sgst_percentage  = product.sgstPercentage,
                        igst_percentage  = product.igstPercentage,
                        official_uqc     = product.officialUqc,
                        hsn_description  = product.hsnDescription,
                        cess_rate        = product.cessRate,
                        supply_classification = product.supplyClassification,
                        category         = product.category,
                        is_purchased     = product.isPurchased
                    )
                )
                if (response.product_id > 0) {
                    db.productDao().setServerId(product.id, response.product_id)
                    Log.d(SYNC_TAG, "  product '${product.name}' → server id ${response.product_id}")
                }

                // Always also register globally — best-effort.
                runCatching {
                    api.registerGlobalProduct(
                        token,
                        GlobalProductRegisterRequest(
                            name      = product.name,
                            variant   = product.variant,
                            hsn_code  = product.hsnCode
                        )
                    )
                }.onFailure {
                    Log.w(SYNC_TAG, "  global register failed for '${product.name}': ${it.message}")
                }
            } catch (e: Exception) {
                Log.e(SYNC_TAG, "syncShopProducts: '${product.name}' push failed", e)
                // Leave the row unsynced; next pass retries it.
            }
        }
    }

    /**
     * Pushes locally-created custom categories (serverId == null), then
     * pulls the shop's categories so custom entries created on other
     * devices appear in this device's dropdown. Best-effort: categories
     * are also stored as plain strings on products, so failure never
     * blocks product/invoice sync.
     */
    suspend fun syncCategories() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: run {
                Log.w(SYNC_TAG, "syncCategories skipped — no auth token")
                return
            }
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        // Push
        val pending = db.productCategoryDao().getUnsynced()
        if (pending.isNotEmpty()) {
            Log.d(SYNC_TAG, "syncCategories: ${pending.size} unsynced category(ies)")
            try {
                val dtos = pending.map { CategoryDto(local_id = it.id, name = it.name) }
                val response = api.syncCategories(token, CategorySyncRequest(dtos))
                response.category_id_map.forEach { (localIdStr, serverId) ->
                    localIdStr.toIntOrNull()?.let { db.productCategoryDao().setServerId(it, serverId) }
                }
            } catch (e: Exception) {
                Log.e(SYNC_TAG, "syncCategories push failed", e)
            }
        }

        // Pull (seed dropdown memory from other devices)
        try {
            val shopId = getShopIdString()
            val remote = api.getCategories(token).categories
            for (item in remote) {
                if (db.productCategoryDao().getByName(item.name, shopId) == null) {
                    db.productCategoryDao().insertIgnore(
                        com.example.easy_billing.db.ProductCategory(
                            serverId = item.id,
                            shopId = shopId,
                            name = item.name
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(SYNC_TAG, "syncCategories pull skipped: ${e.message}")
        }
    }

    /**
     * Pushes locally-created/edited customers (serverId == null). The
     * server upserts by (shop_id, phone) and returns local_id →
     * server_id, so two devices that created the same phone offline
     * converge to one server id. No FK dependency: invoices carry their
     * own snapshot, so ordering is for cleanliness only.
     */
    suspend fun syncCustomers() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: run {
                Log.w(SYNC_TAG, "syncCustomers skipped — no auth token")
                return
            }
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val pending = db.customerDao().getUnsynced()
        if (pending.isEmpty()) return
        Log.d(SYNC_TAG, "syncCustomers: ${pending.size} unsynced customer(s)")

        try {
            val dtos = pending.map {
                CustomerDto(
                    local_id      = it.id,
                    phone         = it.phone,
                    name          = it.name,
                    customer_type = it.customerType,
                    business_name = it.businessName,
                    gstin         = it.gstin,
                    state         = it.state,
                    state_code    = it.stateCode,
                    updated_at    = it.updatedAt
                )
            }
            val response = api.syncCustomers(token, CustomerSyncRequest(dtos))
            response.customer_id_map.forEach { (localIdStr, serverId) ->
                localIdStr.toIntOrNull()?.let { db.customerDao().setServerId(it, serverId) }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "syncCustomers push failed", e)
        }
    }

    /** Shop id as the string form products/categories use locally. */
    private fun getShopIdString(): String {
        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
        return try {
            prefs.getString("SHOP_ID", null) ?: prefs.getInt("SHOP_ID", 0).toString()
        } catch (e: ClassCastException) {
            prefs.getInt("SHOP_ID", 0).toString()
        }
    }

    /**
     * Pushes every unsynced row in `purchase_table` (and its line
     * items) in a single batch. The backend echoes a
     * `local_id → server_id` map which we apply locally so future
     * sync passes don't re-push the same rows.
     */
    suspend fun syncPurchases(): SyncResult {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: run {
                Log.w(SYNC_TAG, "syncPurchases skipped — no auth token")
                return SyncResult.Skipped("not signed in")
            }
        val db = AppDatabase.getDatabase(context)

        val pending = db.purchaseDao().getUnsynced()
        Log.d(SYNC_TAG, "syncPurchases: ${pending.size} unsynced purchase(s) in queue")
        if (pending.isEmpty()) return SyncResult.NothingToDo

        return pushPurchases(token, pending)
    }

    /**
     * Push a SINGLE purchase + its items immediately. Called inline
     * by [com.example.easy_billing.repository.PurchaseRepository]
     * after the local transaction commits, so the user gets an
     * actionable result back instead of having to wait for the
     * background SyncCoordinator.
     */
    suspend fun pushPurchaseImmediately(purchaseId: Int): SyncResult {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return SyncResult.Skipped("not signed in")
        val db = AppDatabase.getDatabase(context)

        // Make sure the products this purchase references have been
        // pushed first — otherwise their `shop_product_id` will come
        // through as null and the backend has no canonical reference.
        runCatching { syncShopProducts() }.onFailure {
            Log.w(SYNC_TAG, "pushPurchaseImmediately: shop product pre-sync failed: ${it.message}")
        }

        // 🔥 ALSO SYNC CREDIT ACCOUNTS BEFORE PURCHASES
        // If the user created a new account for this purchase, it MUST
        // land on the server first to get its `serverId`.
        runCatching { syncAccounts() }.onFailure {
            Log.w(SYNC_TAG, "pushPurchaseImmediately: account pre-sync failed: ${it.message}")
        }
        runCatching { syncCredit() }.onFailure {
            Log.w(SYNC_TAG, "pushPurchaseImmediately: transaction pre-sync failed: ${it.message}")
        }

        val purchase = db.purchaseDao().getById(purchaseId)
            ?: return SyncResult.Skipped("local purchase not found")
        if (purchase.isSynced) return SyncResult.NothingToDo

        val mainResult = pushPurchases(token, listOf(purchase))

        // Also flush gst_purchase_records — savePurchase wrote one
        // row per line item that has to land in the backend's
        // `gst_purchase_record` table. Failures here don't override
        // the main purchase result; they're logged and retried on
        // the next sync pass.
        runCatching { syncGstPurchases() }.onFailure {
            Log.w(SYNC_TAG, "pushPurchaseImmediately: gst records flush failed: ${it.message}")
        }

        // Also push the inventory logs created by this purchase immediately.
        runCatching { syncInventory() }.onFailure {
            Log.w(SYNC_TAG, "pushPurchaseImmediately: inventory logs sync failed: ${it.message}")
        }

        return mainResult
    }

    /** Shared push routine — keeps the DTO mapping in one place. */
    private suspend fun pushPurchases(
        token: String,
        pending: List<com.example.easy_billing.db.Purchase>
    ): SyncResult {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val productDao = db.productDao()
        val itemDao    = db.purchaseItemDao()

        val dtoBatch = pending.map { p ->
            val items = itemDao.getByPurchase(p.id).map { item ->
                val serverProductId = item.productId
                    ?.let { productDao.getById(it)?.serverId }

                PurchaseItemDto(
                    local_id                 = item.id,
                    shop_product_id          = serverProductId,
                    product_name             = item.productName,
                    variant                  = item.variant,
                    hsn_code                 = item.hsnCode,
                    quantity                 = item.quantity,
                    unit                     = item.unit,
                    taxable_amount           = item.taxableAmount,
                    invoice_value            = item.invoiceValue,
                    cost_price               = item.costPrice,
                    purchase_cgst_percentage = item.purchaseCgstPercentage,
                    purchase_sgst_percentage = item.purchaseSgstPercentage,
                    purchase_igst_percentage = item.purchaseIgstPercentage,
                    purchase_cgst_amount     = item.purchaseCgstAmount,
                    purchase_sgst_amount     = item.purchaseSgstAmount,
                    purchase_igst_amount     = item.purchaseIgstAmount,
                    sales_cgst_percentage    = item.salesCgstPercentage,
                    sales_sgst_percentage    = item.salesSgstPercentage,
                    sales_igst_percentage    = item.salesIgstPercentage,
                    cess_percentage          = item.cessPercentage,
                    cess_amount              = item.cessAmount,
                    eligibility_for_itc      = item.eligibilityForItc,
                    availed_itc_igst         = item.availedItcIgst,
                    availed_itc_cgst         = item.availedItcCgst,
                    availed_itc_sgst         = item.availedItcSgst,
                    availed_itc_cess         = item.availedItcCess,
                    hsn_description          = item.hsnDescription,
                    official_uqc             = item.officialUqc,
                    supply_classification    = item.supplyClassification
                )
            }
            val shopId = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getInt("SHOP_ID", 1)
            val serverCreditAccountId = p.creditAccountId?.let { localId ->
                db.creditAccountDao().getById(localId, shopId)?.serverId
            }

            PurchaseDto(
                local_id         = p.id,
                invoice_number   = p.invoiceNumber,
                supplier_gstin   = p.supplierGstin,
                supplier_name    = p.supplierName,
                state            = p.state,
                taxable_amount   = p.taxableAmount,
                cgst_percentage  = p.cgstPercentage,
                sgst_percentage  = p.sgstPercentage,
                igst_percentage  = p.igstPercentage,
                cgst_amount      = p.cgstAmount,
                sgst_amount      = p.sgstAmount,
                igst_amount      = p.igstAmount,
                invoice_value    = p.invoiceValue,
                invoice_date     = p.invoiceDate,
                is_credit        = p.isCredit,
                credit_account_id = serverCreditAccountId,
                created_at       = p.createdAt,
                place_of_supply_code = p.placeOfSupplyCode,
                reverse_charge   = p.reverseCharge,
                invoice_type     = p.invoiceType,
                supply_type      = p.supplyType,
                cess_paid        = p.cessPaid,
                eligibility_for_itc = p.eligibilityForItc,
                availed_itc_integrated_tax = p.availedItcIntegratedTax,
                availed_itc_central_tax = p.availedItcCentralTax,
                availed_itc_state_tax = p.availedItcStateTax,
                availed_itc_cess = p.availedItcCess,
                purchase_source = p.purchaseSource,
                items            = items
            )
        }

        return try {
            Log.d(SYNC_TAG, "pushPurchases: POST /purchases/sync with ${dtoBatch.size} purchase(s)")
            val response = api.syncPurchases(
                token, PurchaseSyncRequest(dtoBatch)
            )
            Log.d(SYNC_TAG, "pushPurchases: server replied success_count=${response.success_count}, " +
                "purchase_id_map=${response.purchase_id_map}")
            response.purchase_id_map.forEach { (localIdStr, serverId) ->
                val localId = localIdStr.toIntOrNull() ?: return@forEach
                db.purchaseDao().markSynced(localId, serverId)
                db.purchaseItemDao().markAllSyncedForPurchase(localId)
            }
            // Fallback: if server didn't echo a map, mark everything
            // we sent as synced so we don't loop forever.
            if (response.purchase_id_map.isEmpty()) {
                pending.forEach {
                    db.purchaseDao().markSynced(it.id, null)
                    db.purchaseItemDao().markAllSyncedForPurchase(it.id)
                }
            }
            SyncResult.Pushed(pending.size)
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "pushPurchases: POST /purchases/sync FAILED", e)
            SyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Outcome of a single push attempt — surfaced to the UI. */
    sealed class SyncResult {
        object NothingToDo : SyncResult()
        data class Skipped(val reason: String) : SyncResult()
        data class Pushed(val count: Int) : SyncResult()
        data class Failed(val reason: String) : SyncResult()
    }

    private companion object {
        const val SYNC_TAG = "PurchaseSync"

        /**
         * Serializes [syncBills] across ALL SyncManager instances.
         *
         * The bill save flow (InvoiceActivity) and the Dashboard /
         * periodic sync can both call syncBills() at nearly the same
         * moment. Without this lock, both read the same bill as
         * "unsynced" and each POST it to /bills/create → duplicate
         * rows in the backend bills table (one sale, two entries).
         */
        val billSyncMutex = kotlinx.coroutines.sync.Mutex()
    }

    /**
     * Stable per-install device id, generated once and persisted.
     * Part of the bill idempotency key sent to /bills/create.
     */
    private fun deviceId(): String {
        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
        prefs.getString("DEVICE_ID", null)?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("DEVICE_ID", fresh).apply()
        return fresh
    }

    suspend fun syncPurchaseImportDetails() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val pending = db.purchaseImportDetailsDao().getUnsynced()
        if (pending.isEmpty()) return

        val shopId = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getInt("SHOP_ID", 1)

        val dtos = pending.map { d ->
            val purchase = db.purchaseDao().getById(d.purchaseId)
            val serverId = purchase?.serverId

            PurchaseImportDetailsDto(
                local_id = d.id,
                purchase_id = serverId,
                local_purchase_id = d.localPurchaseId,
                port_code = d.portCode,
                bill_of_entry_number = d.billOfEntryNumber,
                bill_of_entry_date = d.billOfEntryDate,
                bill_of_entry_value = d.billOfEntryValue,
                document_type = d.documentType,
                sez_supplier_gstin = d.sezSupplierGstin,
                sync_status = d.syncStatus,
                device_id = d.deviceId,
                created_at = d.createdAt,
                updated_at = d.updatedAt
            )
        }

        try {
            val response = api.syncPurchaseImportDetails(
                token,
                PurchaseImportDetailsSyncRequest(dtos)
            )
            response.record_id_map.forEach { (localIdStr, _) ->
                val localId = localIdStr.toIntOrNull() ?: return@forEach
                db.purchaseImportDetailsDao().markSynced(localId)
            }
            if (response.record_id_map.isEmpty()) {
                pending.forEach {
                    db.purchaseImportDetailsDao().markSynced(it.id)
                }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "syncPurchaseImportDetails: FAILED", e)
        }
    }

    suspend fun syncImportServices() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val pending = db.importServiceDao().getPendingSyncImportServices()
        if (pending.isEmpty()) return

        val shopId = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getInt("SHOP_ID", 1)

        val deviceId = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("DEVICE_ID", java.util.UUID.randomUUID().toString())

        val dtos = pending.map { d ->
            com.example.easy_billing.network.ImportServiceDto(
                local_id = d.id,
                invoice_number = d.invoiceNumber,
                invoice_date = d.invoiceDate,
                invoice_value = d.invoiceValue,
                place_of_supply = d.placeOfSupply,
                rate = d.rate,
                taxable_value = d.taxableValue,
                igst_paid = d.igstPaid,
                cess_paid = d.cessPaid,
                eligibility_for_itc = d.eligibilityForItc,
                availed_itc_igst = d.availedItcIgst,
                availed_itc_cess = d.availedItcCess,
                sync_status = d.syncStatus,
                device_id = deviceId
            )
        }

        try {
            val response = api.syncImportServices(token, shopId, dtos)
            if (response.isSuccessful) {
                pending.forEach {
                    db.importServiceDao().markAsSynced(it.id)
                }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "syncImportServices: FAILED", e)
        }
    }

    suspend fun pullImportServices() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return
        val shopId = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getInt("SHOP_ID", -1)
        if (shopId == -1) return

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        try {
            val serverRecords = api.getImportServices(token, shopId)
            val dao = db.importServiceDao()

            db.withTransaction {
                for (record in serverRecords) {
                    val localIdToUse = record.local_id ?: record.id
                    val existing = dao.getById(localIdToUse)
                    if (existing != null) {
                        dao.update(
                            existing.apply {
                                invoiceNumber = record.invoice_number
                                invoiceDate = record.invoice_date
                                invoiceValue = record.invoice_value
                                placeOfSupply = record.place_of_supply
                                rate = record.rate
                                taxableValue = record.taxable_value
                                igstPaid = record.igst_paid
                                cessPaid = record.cess_paid
                                eligibilityForItc = record.eligibility_for_itc
                                availedItcIgst = record.availed_itc_igst
                                availedItcCess = record.availed_itc_cess
                                syncStatus = "synced"
                            }
                        )
                    } else {
                        dao.insert(
                            com.example.easy_billing.db.ImportService(
                                id = localIdToUse,
                                invoiceNumber = record.invoice_number,
                                invoiceDate = record.invoice_date,
                                invoiceValue = record.invoice_value,
                                placeOfSupply = record.place_of_supply,
                                rate = record.rate,
                                taxableValue = record.taxable_value,
                                igstPaid = record.igst_paid,
                                cessPaid = record.cess_paid,
                                eligibilityForItc = record.eligibility_for_itc,
                                availedItcIgst = record.availed_itc_igst,
                                availedItcCess = record.availed_itc_cess,
                                syncStatus = "synced"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "pullImportServices: FAILED", e)
        }
    }


    suspend fun syncPurchaseReturns() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val pending = db.purchaseReturnDao().getUnsynced()
        if (pending.isEmpty()) return

        val productDao = db.productDao()
        val dtos = pending.map { r ->
            val serverProductId = r.productId?.let { productDao.getById(it)?.serverId }
            val shopId = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getInt("SHOP_ID", 1)
            val serverCreditAccountId = r.creditAccountId?.let { localId ->
                db.creditAccountDao().getById(localId, shopId)?.serverId
            }

            PurchaseReturnDto(
                local_id                = r.id,
                shop_id                 = r.shopId,
                shop_product_id         = serverProductId,
                product_name            = r.productName,
                variant_name            = r.variantName,
                hsn_code                = r.hsnCode,
                quantity_returned       = r.quantityReturned,
                taxable_amount          = r.taxableAmount,
                invoice_value           = r.invoiceValue,
                cgst_percentage         = r.cgstPercentage,
                sgst_percentage         = r.sgstPercentage,
                igst_percentage         = r.igstPercentage,
                cgst_amount             = r.cgstAmount,
                sgst_amount             = r.sgstAmount,
                igst_amount             = r.igstAmount,
                state                   = r.state,
                supplier_gstin          = r.supplierGstin,
                supplier_name           = r.supplierName,
                is_credit               = r.isCredit,
                credit_account_id       = serverCreditAccountId,
                created_at              = r.createdAt,
                // Debit Note fields (v25) — null-safe for legacy rows
                note_number             = r.noteNumber,
                note_date               = r.noteDate,
                note_type               = r.noteType,
                original_invoice_id     = r.originalInvoiceId,
                original_invoice_number = r.originalInvoiceNumber,
                original_invoice_date   = r.originalInvoiceDate,
                place_of_supply         = r.placeOfSupply,
                supply_type             = r.supplyType,
                cess_amount             = r.cessAmount,
                tax_amount              = r.cgstAmount + r.sgstAmount + r.igstAmount + r.cessAmount,
                total_amount            = r.invoiceValue,
                document_type           = r.documentType,
                document_nature         = r.documentNature,
                document_series         = r.documentSeries,
                pre_gst                 = r.preGst,
                reason_for_issuing_document = r.reasonForIssuingDocument,
                note_refund_voucher_value = r.noteRefundVoucherValue,
                rate                    = r.rate,
                eligibility_for_itc     = r.eligibilityForItc,
                availed_itc_integrated_tax = r.availedItcIntegratedTax,
                availed_itc_central_tax  = r.availedItcCentralTax,
                availed_itc_state_tax    = r.availedItcStateTax,
                availed_itc_cess         = r.availedItcCess,
                invoice_type            = r.invoiceType,
                place_of_supply_code    = r.placeOfSupplyCode
            )
        }

        try {
            api.syncPurchaseReturns(token, PurchaseReturnSyncRequest(dtos))
            pending.forEach { db.purchaseReturnDao().markSynced(it.id) }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "syncPurchaseReturns: failed", e)
        }
    }

    /**
     * Pushes locally-created Credit Notes (sales returns) to the backend.
     *
     * Offline-safe: rows with syncStatus != "synced" are collected and
     * sent in one batch. On success the server echoes a local_id → server_id
     * map; rows are marked "synced". On any failure rows remain "pending"
     * and will be retried on the next syncAll() pass.
     *
     * Idempotent: the backend is expected to de-duplicate on note_number.
     */
    suspend fun syncCreditNotes() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: run {
                Log.w(SYNC_TAG, "syncCreditNotes skipped — no auth token")
                return
            }
        val db  = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val pending = db.creditNoteDao().getUnsynced()
        Log.d(SYNC_TAG, "syncCreditNotes: ${pending.size} unsynced credit note(s)")
        if (pending.isEmpty()) return

        val itemDao    = db.creditNoteItemDao()
        val productDao = db.productDao()

        val dtoBatch = pending.map { note ->
            val items = itemDao.getByNote(note.id).map { item ->
                val serverPid = productDao.getById(item.productId)?.serverId
                CreditNoteItemDto(
                    product_id           = serverPid ?: item.productId,
                    product_name         = item.productName,
                    variant              = item.variant,
                    hsn_code             = item.hsnCode,
                    unit                 = item.unit,
                    quantity_sold        = item.quantitySold,
                    quantity_returned    = item.quantityReturned,
                    rate                 = item.rate,
                    cost_price_used      = item.costPriceUsed,
                    taxable_value        = item.taxableValue,
                    gst_rate             = item.gstRate,
                    cgst_amount          = item.cgstAmount,
                    sgst_amount          = item.sgstAmount,
                    igst_amount          = item.igstAmount,
                    cess_amount          = item.cessAmount,
                    tax_amount           = item.taxAmount,
                    total_amount         = item.totalAmount,
                    original_bill_item_id = item.originalBillItemId
                )
            }
            CreditNoteDto(
                local_id                = note.id,
                note_number             = note.noteNumber,
                note_date               = note.noteDate,
                note_type               = note.noteType,
                note_supply_type        = note.noteSupplyType,
                original_invoice_id     = note.originalInvoiceId,
                original_invoice_number = note.originalInvoiceNumber,
                original_invoice_date   = note.originalInvoiceDate,
                customer_name           = note.customerName,
                customer_gstin          = note.customerGstin,
                place_of_supply         = note.placeOfSupply,
                reverse_charge          = note.reverseCharge,
                supply_type             = note.supplyType,
                ur_type                 = note.urType,
                document_type           = note.documentType,
                document_nature         = note.documentNature,
                document_series         = if (note.documentSeries.isNullOrBlank()) {
                    val prefix = note.noteNumber.split("-").firstOrNull() ?: note.noteNumber.split("_").firstOrNull()
                    if (!prefix.isNullOrBlank()) prefix else (if (note.noteType == "C") "CN" else "DN")
                } else {
                    note.documentSeries
                },
                taxable_value           = note.taxableValue,
                tax_amount              = note.taxAmount,
                cess_amount             = note.cessAmount,
                total_amount            = note.totalAmount,
                cgst_amount             = note.cgstAmount,
                sgst_amount             = note.sgstAmount,
                igst_amount             = note.igstAmount,
                created_at              = note.createdAt,
                items                   = items
            )
        }

        try {
            Log.d(SYNC_TAG, "syncCreditNotes: POST /credit-notes/sync with ${dtoBatch.size} note(s)")
            val response = api.syncCreditNotes(token, CreditNoteSyncRequest(dtoBatch))
            Log.d(SYNC_TAG, "syncCreditNotes: server replied success=${response.success_count}")

            // Mark synced via server's echo map
            response.note_id_map.forEach { (localIdStr, _) ->
                val localId = localIdStr.toIntOrNull() ?: return@forEach
                db.creditNoteDao().markSynced(localId)
            }
            // Fallback — server didn't echo map but accepted the request (2xx)
            if (response.note_id_map.isEmpty()) {
                pending.forEach { db.creditNoteDao().markSynced(it.id) }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "syncCreditNotes: POST /credit-notes/sync FAILED", e)
            pending.forEach { db.creditNoteDao().markFailed(it.id) }
        }
    }

    suspend fun syncScrapEntries() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val pending = db.scrapDao().getUnsynced()
        if (pending.isEmpty()) return

        val productDao = db.productDao()
        val dtos = pending.map { s ->
            val serverProductId = s.productId?.let { productDao.getById(it)?.serverId }
            ScrapDto(
                local_id        = s.id,
                shop_id         = s.shopId,
                shop_product_id = serverProductId,
                product_name    = s.productName,
                variant_name    = s.variantName,
                hsn_code        = s.hsnCode,
                quantity        = s.quantity,
                taxable_amount  = s.taxableAmount,
                invoice_value   = s.invoiceValue,
                cgst_percentage = s.cgstPercentage,
                sgst_percentage = s.sgstPercentage,
                igst_percentage = s.igstPercentage,
                cgst_amount     = s.cgstAmount,
                sgst_amount     = s.sgstAmount,
                igst_amount     = s.igstAmount,
                state           = s.state,
                reason          = s.reason,
                created_at      = s.createdAt
            )
        }

        try {
            api.syncScrap(token, ScrapSyncRequest(dtos))
            pending.forEach { db.scrapDao().markSynced(it.id) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun syncBills() = billSyncMutex.withLock {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return@withLock

        // Re-read INSIDE the lock: a concurrent caller that just finished
        // has already marked these bills synced, so we see fresh flags.
        val bills = db.billDao().getUnsyncedBills()
        val productDao = db.productDao()

        for (bill in bills) {

            try {

                val items = db.billItemDao().getItemsForBill(bill.id)
                val gstInvoice = db.gstSalesInvoiceDao().getByBillId(bill.id)
                val gstInvoiceItems = if (gstInvoice != null) {
                    db.gstSalesInvoiceItemDao().getByInvoice(gstInvoice.id).associateBy { it.productId }
                } else {
                    emptyMap()
                }

                val apiItems = items.mapNotNull {

                    val product = productDao.getById(it.productId)
                    val serverId = product?.serverId

                    if (serverId == null || serverId <= 0) {
                        println("❌ Skipping unsynced product: ${product?.name}")
                        return@mapNotNull null
                    }

                    val gstItem = gstInvoiceItems[it.productId]

                    BillItemRequest(
                        shop_product_id = serverId,
                        product_name = product.name,
                        quantity = it.quantity,
                        variant = product.variant,
                        unit = product.unit ?: "unit",
                        unit_price = gstItem?.sellingPrice ?: product.price,
                        line_subtotal = gstItem?.taxableAmount ?: it.subTotal,
                        discount_amount = 0.0,
                        taxable_amount = gstItem?.taxableAmount ?: it.subTotal,
                        gst_rate = gstItem?.let { g -> g.salesCgstPercentage + g.salesSgstPercentage + g.salesIgstPercentage } ?: 0.0,
                        cgst_rate = gstItem?.salesCgstPercentage ?: 0.0,
                        sgst_rate = gstItem?.salesSgstPercentage ?: 0.0,
                        igst_rate = gstItem?.salesIgstPercentage ?: 0.0,
                        cgst_amount = gstItem?.cgstAmount ?: 0.0,
                        sgst_amount = gstItem?.sgstAmount ?: 0.0,
                        igst_amount = gstItem?.igstAmount ?: 0.0,
                        cess_amount = 0.0,
                        total_amount = gstItem?.netValue ?: it.subTotal,
                        hsn_code = gstItem?.hsnCode ?: ""
                    )
                }

                if (apiItems.size != items.size) {
                    println("❌ Skipping bill ${bill.id} → invalid products")
                    continue
                }

                // created_at must always be the bill's actual wall-clock time in the
                // APP timezone (AppTime — mirrors backend app/util/time_utils.py). If it
                // arrives null the server falls back to local_now() (the SYNC time),
                // which mis-places the bill on time-based reports — e.g. a 12am bill
                // made/synced in the evening shows up around 10pm. So we never send null,
                // and we parse AND format in the app timezone (never device-local/UTC).
                val isoDate: String = run {
                    val patterns = listOf(
                        "dd/MM/yyyy HH:mm:ss",
                        "dd/MM/yyyy HH:mm",
                        "yyyy-MM-dd'T'HH:mm:ss",
                        "yyyy-MM-dd HH:mm:ss"
                    )
                    val parsed = patterns.firstNotNullOfOrNull { pat ->
                        try {
                            com.example.easy_billing.util.AppTime.formatter(pat)
                                .apply { isLenient = false }
                                .parse(bill.date)
                        } catch (_: Exception) { null }
                    }
                    com.example.easy_billing.util.AppTime.isoDateTime()
                        .format(parsed ?: java.util.Date())
                }

                val supplyType = if (gstInvoice?.totalIgst != null && gstInvoice.totalIgst > 0) "interstate" else "intrastate"

                // ── Resolve customer state name + code as a consistent pair ──
                // Code: from the saved invoice, else derived from the saved
                //       state name, else from the customer GSTIN.
                // Name: as typed on the invoice, else looked up from the code.
                // When both end up null the backend fills in the shop's own
                // state (B2C local sale).
                val resolvedStateCode = gstInvoice?.customerStateCode
                    ?.takeIf { it.isNotBlank() }
                    ?: GstEngine.getStateCodeFromName(gstInvoice?.customerState)
                    ?: GstEngine.getStateCode(gstInvoice?.customerGst).takeIf { !it.isNullOrBlank() }
                val resolvedStateName = gstInvoice?.customerState
                    ?.takeIf { it.isNotBlank() }
                    ?: resolvedStateCode?.let { GstEngine.INDIA_STATES[it] }

                val request = CreateBillRequest(
                    bill_number = "",
                    items = apiItems,
                    payment_method = bill.paymentMethod,
                    discount = bill.discount,
                    gst = bill.gst,
                    total_amount = bill.total,
                    
                    subtotal = gstInvoice?.subtotal ?: (bill.total - bill.gst + bill.discount),
                    discount_amount = bill.discount,
                    taxable_amount = gstInvoice?.subtotal ?: (bill.total - bill.gst),
                    
                    cgst_amount = gstInvoice?.totalCgst ?: 0.0,
                    sgst_amount = gstInvoice?.totalSgst ?: 0.0,
                    igst_amount = gstInvoice?.totalIgst ?: 0.0,
                    cess_amount = 0.0,
                    gst_amount = gstInvoice?.totalTax ?: bill.gst,
                    
                    round_off = 0.0,
                    final_amount = gstInvoice?.grandTotal ?: bill.total,
                    
                    gst_scheme = gstInvoice?.gstScheme ?: "Regular",
                    supply_type = supplyType,
                    customer_state = resolvedStateName,
                    customer_state_code = resolvedStateCode,
                    invoice_type = gstInvoice?.invoiceType ?: bill.customerType,
                    is_gst_invoice = gstInvoice != null,

                    // Idempotency key — backend dedupes on this, so a
                    // retried/concurrent sync can't create two entries.
                    client_bill_id = bill.id,
                    client_device_id = deviceId(),

                    created_at = isoDate,

                    // Cancelled-before-first-sync: arrive already voided
                    // so the bill never counts in server reports.
                    is_cancelled = bill.isCancelled,
                    cancelled_at = bill.cancelledAt
                )

                val response = api.createBill(token, request)

                if (response.bill_number.isNotEmpty()) {
                    db.billDao().updateBillNumber(bill.id, response.bill_number)
                    db.gstSalesInvoiceDao().updateInvoiceNumberByBillId(bill.id, response.bill_number)
                    // Purge stale duplicate bill numbers from Room DB.
                    // After a server DB wipe + restart the server reissues numbers
                    // from INV_YYYY_1. If Room DB still has an old bill with the same
                    // number, getByBillNumber in BillDetailsActivity would find the
                    // OLD bill and pass wrong items to SalesReturn/DebitNote.
                    db.billDao().clearDuplicateBillNumbers(response.bill_number, bill.id)
                }

                db.billDao().markBillSynced(bill.id)
                db.billItemDao().markItemsSynced(bill.id)
                // A bill sent as is_cancelled=true was never created on the server
                // (server returns bill_id=-1). Mark cancelSynced immediately so
                // syncBillCancellations() never tries to void a non-existent row.
                if (bill.isCancelled) {
                    db.billDao().markCancelSynced(bill.id)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                continue
            }
        }
    }

    suspend fun syncStoreInfo() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        try {

            // ✅ ALWAYS FETCH FROM BACKEND
            val response = api.getStoreSettings(token)

            if (!response.shop_name.isNullOrBlank()) {

                val store = StoreInfo(
                    name = response.shop_name,
                    address = response.store_address ?: "",
                    phone = response.phone ?: "",
                    gstin = response.store_gstin ?: "",
                    isSynced = true
                )

                db.storeInfoDao().insert(store)
            }

        } catch (e: Exception) {
            // ignore
        }
    }

    suspend fun syncBillingSettings() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        try {

            val response = api.getBillingSettings(token)

            val settings = BillingSettings(
                defaultGst = response.default_gst,
                printerLayout = response.printer_layout
            )

            db.billingSettingsDao().insert(settings)

        } catch (_: Exception) {
            // offline → ignore
        }
    }

    suspend fun syncCredit() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

        val token = prefs.getString("TOKEN", null) ?: return
        val shopId = prefs.getInt("SHOP_ID", 1)

        val txns = db.creditTransactionDao().getUnsynced(shopId)

        for (txn in txns) {

            try {

                // ✅ FIX: pass shopId
                val account = db.creditAccountDao().getById(txn.accountId, shopId)

                if (account?.serverId == null || account.serverId == -1) {
                    println("⏳ Waiting for account sync → txnId=${txn.id}")
                    continue
                }

                val res = api.syncCredit(
                    token,
                    CreditSyncRequest(
                        account_id = account.serverId,
                        amount = txn.amount,
                        type = txn.type,
                        reference_invoice = txn.referenceInvoice
                    )
                )

                if (res.isSuccessful) {
                    db.creditTransactionDao().markSynced(txn.id, shopId)
                } else {
                    println("❌ ERROR: ${res.code()} ${res.errorBody()?.string()}")
                    continue
                }

            } catch (e: Exception) {
                e.printStackTrace()
                continue
            }
        }
    }

    suspend fun syncAccounts() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

        val token = prefs.getString("TOKEN", null) ?: return
        val shopId = prefs.getInt("SHOP_ID", 1)

        // ✅ FIX: filter by shopId
        val accounts = db.creditAccountDao().getAll(shopId)
            .filter { !it.isSynced }

        for (acc in accounts) {
            try {
                val res = api.createCreditAccount(
                    token,
                    CreateCreditAccountRequest(acc.name, acc.phone)
                )

                db.creditAccountDao().updateServerId(
                    acc.id,
                    res.id,
                    shopId
                )

            } catch (e: Exception) {
                e.printStackTrace()
                continue
            }
        }
    }

    suspend fun pullAccountsFromServer() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

        val token = prefs.getString("TOKEN", null) ?: return
        val shopId = prefs.getInt("SHOP_ID", 1)

        try {

            val accounts = api.getCreditAccounts(token)

            for (acc in accounts) {

                val existing = db.creditAccountDao()
                    .getByServerId(acc.id, shopId)

                if (existing != null) {
                    db.creditAccountDao().insert(
                        existing.copy(
                            name = acc.name,
                            phone = acc.phone,
                            dueAmount = acc.due_amount,
                            isActive = true
                        )
                    )
                } else {
                    db.creditAccountDao().insert(
                        CreditAccount(
                            serverId = acc.id,
                            name = acc.name,
                            phone = acc.phone,
                            dueAmount = acc.due_amount,
                            isSynced = true,
                            shopId = shopId,
                            isActive = true
                        )
                    )
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun syncInventory() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        val logs = db.inventoryLogDao().getUnsyncedLogs()
        if (logs.isEmpty()) return

        val productDao = db.productDao()

        val validRequests = mutableListOf<InventoryLogRequest>()
        val syncedLogIds = mutableListOf<Int>()

        for (log in logs) {

            val product = productDao.getById(log.productId)

            // ❌ skip if not synced to backend
            if (product?.serverId == null || product.serverId == 0) {
                println("⏳ Skipping log ${log.id} → product not synced yet")
                continue
            }

            validRequests.add(
                InventoryLogRequest(
                    product_id = product.serverId,
                    type = log.type,
                    quantity = log.quantity,
                    price = log.price,
                    date = log.date   // 🔥 REQUIRED
                )
            )

            syncedLogIds.add(log.id)
        }

        // ❌ nothing valid to send
        if (validRequests.isEmpty()) return

        try {

            val response = api.syncInventory(
                token,
                validRequests
            )

            if (response.isSuccessful) {
                db.inventoryLogDao().markAsSynced(syncedLogIds)

                // Update isSynced status for Inventory rows
                val affectedPids = logs.map { it.productId }.distinct()
                for (pid in affectedPids) {
                    if (db.inventoryLogDao().getUnsyncedCountForProduct(pid) == 0) {
                        val inv = db.inventoryDao().getInventory(pid)
                        if (inv != null) db.inventoryDao().update(inv.copy(isSynced = true))
                    }
                }

                println("✅ Inventory synced: ${syncedLogIds.size}")
            } else {

                println("❌ Sync failed: ${response.code()}")
            }

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }

    suspend fun pullInventory() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        try {

            val serverInventory = api.getInventory(token)

            println("📦 SERVER INVENTORY SIZE = ${serverInventory.size}")

            val productDao = db.productDao()
            val inventoryDao = db.inventoryDao()

            // 🔥 Load products ONCE
            val products = productDao.getAll()
            val productMap = products.associateBy { it.serverId }

            val missingProductIds = mutableSetOf<Int>()

            db.withTransaction {

                for (item in serverInventory) {

                    println("➡️ Processing product_id=${item.product_id}, stock=${item.stock}")

                    // 🔥 TRY TO FIND PRODUCT
                    val product = productMap[item.product_id]
                        ?: productDao.getByServerId(item.product_id)

                    if (product == null) {
                        println("⚠️ Product missing locally: ${item.product_id}")
                        missingProductIds.add(item.product_id)
                        continue
                    }

                    val existing = inventoryDao.getInventoryIncludingInactive(product.id)

                    if (existing != null) {
                        // 🔥 PROTECT LOCAL CHANGES
                        if (!existing.isSynced) {
                            println("⏳ Skipping pull for productId=${product.id} → local has unsynced changes")
                            continue
                        }

                        // For purchased products, the local batch ledger is the ground truth for average cost.
                        // Overwriting it with the server's avg_cost (which is not batch-aware and doesn't
                        // update on scrap/sales) causes it to revert to outdated values.
                        val resolvedAvgCost = if (product.isPurchased) {
                            existing.averageCost
                        } else {
                            0.0
                        }

                        inventoryDao.update(
                            existing.copy(
                                currentStock = item.stock,
                                averageCost = resolvedAvgCost,
                                isActive = item.is_active,
                                isSynced = true
                            )
                        )
                        //InventoryValuation.ensureSyntheticBatch(db, product.id)
                        println("✅ Updated inventory for productId=${product.id}")
                    } else {

                        inventoryDao.insert(
                            Inventory(
                                productId = product.id,
                                currentStock = item.stock,
                                averageCost = item.avg_cost,
                                isActive = item.is_active,
                                isSynced = true
                            )
                        )
                        //InventoryValuation.ensureSyntheticBatch(db, product.id)

                        println("✅ Inserted inventory for productId=${product.id}")
                    }
                }
            }

            // 🔥 FIX MISSING PRODUCTS (CRITICAL FOR REINSTALL CASE)
            if (missingProductIds.isNotEmpty()) {

                println("🔄 Missing products detected → refetching products")

                try {

                    val backendProducts =
                        RetrofitClient.api.getMyProducts(token)

                    // Resolve the shopId to use for missing product rows.
                    // Always use the numeric SHOP_ID (consistent with
                    // ProductRepository.currentShopId).
                    val missingShopId = try {
                        val p = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
                        p.getString("SHOP_ID", null) ?: p.getInt("SHOP_ID", 0).toString()
                    } catch (e: ClassCastException) {
                        context.getSharedPreferences("auth", Context.MODE_PRIVATE)
                            .getInt("SHOP_ID", 0).toString()
                    }

                    backendProducts.forEach { bp ->

                        if (missingProductIds.contains(bp.id)) {

                            // Check if the product already exists (e.g. from a
                            // previous partial sync run) so we update rather than
                            // insert a duplicate row.
                            var alreadyLocal = productDao.getByServerId(bp.id)
                            
                            if (alreadyLocal == null) {
                                // Try fallback check by name and variant to prevent breaking UNIQUE constraint
                                val validShopIds = listOf(missingShopId, "")
                                alreadyLocal = productDao.getByNameAndVariant(bp.name, bp.variant ?: "", validShopIds)
                            }

                            if (alreadyLocal != null) {
                                // Exists — update fields, preserve Room local id.
                                productDao.update(
                                    alreadyLocal.copy(
                                        name           = bp.name,
                                        variant        = bp.variant ?: alreadyLocal.variant.orEmpty(),
                                        unit           = bp.unit ?: alreadyLocal.unit,
                                        price          = bp.price,
                                        serverId       = bp.id,
                                        isActive       = bp.is_active,
                                        isPurchased    = bp.is_purchased,
                                        shopId         = missingShopId,
                                        hsnCode        = bp.hsn_code ?: alreadyLocal.hsnCode,
                                        defaultGstRate = bp.default_gst_rate ?: alreadyLocal.defaultGstRate,
                                        cgstPercentage = bp.cgst_percentage,
                                        sgstPercentage = bp.sgst_percentage,
                                        igstPercentage = bp.igst_percentage,
                                        officialUqc    = bp.official_uqc ?: alreadyLocal.officialUqc,
                                        hsnDescription = bp.hsn_description ?: alreadyLocal.hsnDescription,
                                        cessRate       = bp.cess_rate,
                                        category       = bp.category.ifBlank { alreadyLocal.category }
                                    )
                                )
                            } else {
                                // Truly missing — insert with id = 0 so Room
                                // auto-generates the local primary key. Never
                                // set id = server_id; that conflates two
                                // independent id spaces and breaks upsert logic.
                                productDao.upsert(
                                    com.example.easy_billing.db.Product(
                                        id             = 0,
                                        serverId       = bp.id,
                                        name           = bp.name,
                                        variant        = bp.variant ?: "",
                                        unit           = bp.unit,
                                        price          = bp.price,
                                        trackInventory = true,
                                        isCustom       = false,
                                        isActive       = bp.is_active,
                                        isPurchased    = bp.is_purchased,
                                        shopId         = missingShopId,
                                        hsnCode        = bp.hsn_code,
                                        defaultGstRate = bp.default_gst_rate ?: 0.0,
                                        cgstPercentage = bp.cgst_percentage,
                                        sgstPercentage = bp.sgst_percentage,
                                        igstPercentage = bp.igst_percentage,
                                        officialUqc    = bp.official_uqc,
                                        hsnDescription = bp.hsn_description,
                                        cessRate       = bp.cess_rate,
                                        category       = bp.category
                                    )
                                )
                            }

                            println("✅ Recovered missing product ${bp.id}")
                        }
                    }

                    // 🔥 RE-RUN INVENTORY SYNC ONCE MORE
                    println("🔁 Re-running inventory sync after product fix")
                    pullInventory()

                } catch (e: Exception) {
                    println("❌ Failed to refetch missing products: ${e.message}")
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun pullPurchases() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return

        try {
            val serverPurchases = api.getPurchases(token)
            println("📦 SERVER PURCHASES SIZE = ${serverPurchases.size}")

            val productDao = db.productDao()
            val purchaseDao = db.purchaseDao()
            val purchaseItemDao = db.purchaseItemDao()

            val products = productDao.getAll()
            val productMap = products.associateBy { it.serverId }

            db.withTransaction {
                for (p in serverPurchases) {
                    // Check if purchase already exists locally by server ID
                    val existing = purchaseDao.getByServerId(p.id)
                    if (existing != null) {
                        println("⏳ Skipping pull for purchase serverId=${p.id} → already exists")
                        continue
                    }

                    // Insert purchase header
                    val newPurchaseId = purchaseDao.insert(
                        com.example.easy_billing.db.Purchase(
                            id = 0,
                            invoiceNumber = p.invoice_number,
                            supplierGstin = p.supplier_gstin,
                            supplierName = p.supplier_name,
                            state = p.state,
                            taxableAmount = p.taxable_amount,
                            cgstPercentage = p.cgst_percentage,
                            sgstPercentage = p.sgst_percentage,
                            igstPercentage = p.igst_percentage,
                            cgstAmount = p.cgst_amount,
                            sgstAmount = p.sgst_amount,
                            igstAmount = p.igst_amount,
                            invoiceValue = p.invoice_value,
                            invoiceDate = p.invoice_date,
                            createdAt = p.created_at,
                            isSynced = true,
                            serverId = p.id,
                            isCredit = p.is_credit,
                            creditAccountId = p.credit_account_id,
                            creditTransactionId = null,
                            placeOfSupplyCode = p.place_of_supply_code,
                            reverseCharge = p.reverse_charge,
                            invoiceType = p.invoice_type,
                            supplyType = p.supply_type,
                            cessPaid = p.cess_paid,
                            eligibilityForItc = p.eligibility_for_itc,
                            availedItcIntegratedTax = p.availed_itc_integrated_tax,
                            availedItcCentralTax = p.availed_itc_central_tax,
                            availedItcStateTax = p.availed_itc_state_tax,
                            availedItcCess = p.availed_itc_cess,
                            purchaseSource = p.purchase_source
                        )
                    ).toInt()

                    // Insert purchase items
                    for (item in p.items) {
                        val product = item.shop_product_id?.let {
                            productMap[it] ?: productDao.getByServerId(it)
                        }

                        purchaseItemDao.insert(
                            com.example.easy_billing.db.PurchaseItem(
                                id = 0,
                                purchaseId = newPurchaseId,
                                productId = product?.id,
                                productName = item.product_name,
                                variant = item.variant,
                                hsnCode = item.hsn_code,
                                quantity = item.quantity,
                                unit = item.unit,
                                taxableAmount = item.taxable_amount,
                                invoiceValue = item.invoice_value,
                                costPrice = item.cost_price,
                                purchaseCgstPercentage = item.purchase_cgst_percentage,
                                purchaseSgstPercentage = item.purchase_sgst_percentage,
                                purchaseIgstPercentage = item.purchase_igst_percentage,
                                purchaseCgstAmount = item.purchase_cgst_amount,
                                purchaseSgstAmount = item.purchase_sgst_amount,
                                purchaseIgstAmount = item.purchase_igst_amount,
                                salesCgstPercentage = item.sales_cgst_percentage,
                                salesSgstPercentage = item.sales_sgst_percentage,
                                salesIgstPercentage = item.sales_igst_percentage,
                                cessPercentage = item.cess_percentage,
                                cessAmount = item.cess_amount,
                                eligibilityForItc = item.eligibility_for_itc,
                                availedItcIgst = item.availed_itc_igst,
                                availedItcCgst = item.availed_itc_cgst,
                                availedItcSgst = item.availed_itc_sgst,
                                availedItcCess = item.availed_itc_cess,
                                hsnDescription = item.hsn_description,
                                officialUqc = item.official_uqc,
                                supplyClassification = item.supply_classification,
                                isSynced = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun pullInventoryLogs() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return

        try {
            val serverLogs = api.getInventoryLogs(token)
            println("📦 SERVER INVENTORY LOGS SIZE = ${serverLogs.size}")

            val productDao = db.productDao()
            val inventoryLogDao = db.inventoryLogDao()

            val products = productDao.getAll()
            val productMap = products.associateBy { it.serverId }

            db.withTransaction {
                for (item in serverLogs) {
                    val product = productMap[item.product_id] ?: productDao.getByServerId(item.product_id)
                    if (product == null) continue

                    // Check if similar log exists locally to prevent duplicates
                    val existingLogs = inventoryLogDao.getLogs(product.id)
                    val isDuplicate = existingLogs.any {
                        it.type == item.type &&
                        Math.abs(it.quantity - item.quantity) < 0.001 &&
                        Math.abs(it.price - item.price) < 0.001 &&
                        Math.abs(it.date - (item.date ?: 0L)) < 1000 // 1s tolerance
                    }

                    if (!isDuplicate) {
                        inventoryLogDao.insert(
                            com.example.easy_billing.db.InventoryLog(
                                id = 0,
                                productId = product.id,
                                type = item.type,
                                quantity = item.quantity,
                                price = item.price,
                                date = item.date ?: System.currentTimeMillis(),
                                isSynced = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun syncGstProfile() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return

        val profile = db.gstProfileDao().get() ?: return
        if (profile.syncStatus == "synced") return

        try {
            val request = GstProfileRequest(
                gstin = profile.gstin,
                legal_name = profile.legalName,
                trade_name = profile.tradeName,
                gst_scheme = profile.gstScheme,
                registration_type = profile.registrationType,
                state_code = profile.stateCode
            )
            
            // Re-lookup or direct upsert
            val response = api.upsertGstProfile(token, request)
            
            db.gstProfileDao().insert(profile.copy(
                legalName = response.legal_name,
                tradeName = response.trade_name,
                gstScheme = response.gst_scheme,
                registrationType = response.registration_type,
                stateCode = response.state_code,
                syncStatus = "synced"
            ))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun syncGstSales() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return

        val unsynced = db.gstSalesRecordDao().getUnsynced()
        if (unsynced.isEmpty()) return

        val recordsDto = unsynced.map { record ->
            GstSaleRecordDto(
                record_id              = record.id,
                invoice_number         = record.invoiceNumber,
                invoice_date           = record.invoiceDate,
                customer_type          = record.customerType,
                customer_gstin         = record.customerGstin,
                place_of_supply        = record.placeOfSupply,
                supply_type            = record.supplyType,
                total_invoice_value    = record.totalAmount,
                taxable_value          = record.taxableValue,
                cgst_amount            = record.cgstAmount,
                sgst_amount            = record.sgstAmount,
                igst_amount            = record.igstAmount,
                cess_amount            = record.cessAmount,
                hsn_code               = record.hsnCode,
                gst_rate               = record.gstRate,
                device_id              = record.deviceId,
                customer_name          = record.customerName,
                business_name          = record.businessName,
                customer_phone         = record.customerPhone,
                customer_state         = record.customerState,
                customer_state_code    = record.customerStateCode,
                reverse_charge         = record.reverseCharge,
                gstr_invoice_type      = record.gstrInvoiceType,
                ecommerce_gstin        = record.ecommerceGstin,
                ecommerce_operator_name = record.ecommerceOperatorName,
                cess_rate              = record.cessRate,
                uqc                    = record.uqc,
                hsn_description        = record.hsnDescription,
                is_cancelled           = record.isCancelled,
                eco_nature_of_supply   = record.ecoNatureOfSupply,
                eco_document_type      = record.ecoDocumentType,
                eco_supplier_gstin     = record.ecoSupplierGstin,
                eco_supplier_name      = record.ecoSupplierName,
                eco_recipient_gstin    = record.ecoRecipientGstin,
                eco_recipient_name     = record.ecoRecipientName,
                eco_role               = record.ecoRole
            )
        }

        try {
            val response = api.syncGstSales(token, GstSalesSyncRequest(recordsDto))
            if (response.success_count > 0) {
                // For simplicity, mark all as synced. In a robust system, check response.failed_records
                db.gstSalesRecordDao().markAsSynced(unsynced.map { it.id })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun syncGstPurchases() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: run {
                Log.w(SYNC_TAG, "syncGstPurchases skipped — no auth token")
                return
            }

        val unsynced = db.gstPurchaseRecordDao().getUnsynced()
        Log.d(SYNC_TAG, "syncGstPurchases: ${unsynced.size} unsynced gst_purchase_records")
        if (unsynced.isEmpty()) return

        val recordsDto = unsynced.map { record ->
            GstPurchaseRecordDto(
                record_id = record.id,
                vendor_gstin = record.vendorGstin,
                vendor_name = record.vendorName,
                invoice_number = record.invoiceNumber,
                invoice_date = record.invoiceDate,
                total_invoice_value = record.totalInvoiceValue,
                taxable_value = record.taxableValue,
                cgst_amount = record.cgstAmount,
                sgst_amount = record.sgstAmount,
                igst_amount = record.igstAmount,
                cess_amount = record.cessAmount,
                hsn_code = record.hsnCode,
                gst_rate = record.gstRate,
                itc_eligibility = record.itcEligibility
            )
        }

        try {
            Log.d(SYNC_TAG, "syncGstPurchases: POST /gst/purchases/sync with ${recordsDto.size} record(s)")
            val response = api.syncGstPurchases(token, GstPurchaseSyncRequest(recordsDto))
            Log.d(SYNC_TAG, "syncGstPurchases: server replied success_count=${response.success_count}, message=${response.message}")
            // We only get here if the HTTP call returned 2xx. Mark
            // every row synced — even if `success_count` is 0 the
            // server has accepted the payload, and looping forever
            // re-pushing the same rows would mask any real backend
            // problem.
            db.gstPurchaseRecordDao().markAsSynced(unsynced.map { it.id })
            Log.d(SYNC_TAG, "syncGstPurchases: marked ${unsynced.size} record(s) synced")
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "syncGstPurchases: POST /gst/purchases/sync FAILED", e)
            db.gstPurchaseRecordDao().markFailed(unsynced.map { it.id })
        }
    }

    /**
     * Push every pending row from `gst_sales_invoice_table` (with
     * its child items) to the backend in a single batch. Mirrors
     * the shape used by [syncPurchases]:
     *
     *   • Server returns a `local_id → server_id` map; rows are
     *     marked synced + their `server_id` populated.
     *   • A 2xx with no map still flips the rows to `synced` so we
     *     don't loop forever — the server has at least accepted the
     *     payload.
     *   • Anything else marks the rows `failed` so the next pass
     *     retries them.
     */
    suspend fun syncGstInvoices() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: run {
                Log.w(SYNC_TAG, "syncGstInvoices skipped — no auth token")
                return
            }

        val pending = db.gstSalesInvoiceDao().getUnsynced()
        Log.d(SYNC_TAG, "syncGstInvoices: ${pending.size} unsynced GST invoice(s)")
        if (pending.isEmpty()) return

        val itemDao = db.gstSalesInvoiceItemDao()

        val dtoBatch = pending.map { inv ->
            val items = itemDao.getByInvoice(inv.id).map { item ->
                CreateGstSalesItemDto(
                    product_id            = item.productId,
                    product_name          = item.productName,
                    variant_name          = item.variantName,
                    hsn_code              = item.hsnCode,
                    quantity              = item.quantity,
                    selling_price         = item.sellingPrice,
                    taxable_amount        = item.taxableAmount,
                    sales_cgst_percentage = item.salesCgstPercentage,
                    sales_sgst_percentage = item.salesSgstPercentage,
                    sales_igst_percentage = item.salesIgstPercentage,
                    cgst_amount           = item.cgstAmount,
                    sgst_amount           = item.sgstAmount,
                    igst_amount           = item.igstAmount,
                    net_value             = item.netValue,
                    cess_rate             = item.cessRate,
                    cess_amount           = item.cessAmount,
                    uqc                   = item.uqc,
                    hsn_description       = item.hsnDescription,
                    supply_classification = item.supplyClassification
                )
            }
            CreateGstSalesInvoiceDto(
                local_id                = inv.id,
                bill_id                 = inv.billId,
                invoice_type            = inv.invoiceType,
                gst_scheme              = inv.gstScheme,
                customer_name           = inv.customerName,
                business_name           = inv.businessName,
                customer_phone          = inv.customerPhone,
                customer_gst            = inv.customerGst,
                customer_state          = inv.customerState,
                subtotal                = inv.subtotal,
                total_cgst              = inv.totalCgst,
                total_sgst              = inv.totalSgst,
                total_igst              = inv.totalIgst,
                total_tax               = inv.totalTax,
                grand_total             = inv.grandTotal,
                created_at              = inv.createdAt,
                items                   = items,
                invoice_number          = inv.invoiceNumber,
                invoice_date            = inv.invoiceDate,
                reverse_charge          = inv.reverseCharge,
                gstr_invoice_type       = inv.gstrInvoiceType,
                customer_state_code     = inv.customerStateCode,
                ecommerce_gstin         = inv.ecommerceGstin,
                ecommerce_operator_name = inv.ecommerceOperatorName,
                is_cancelled            = inv.isCancelled,
                cancelled_at            = inv.cancelledAt,
                eco_nature_of_supply    = inv.ecoNatureOfSupply,
                eco_document_type       = inv.ecoDocumentType,
                eco_supplier_gstin      = inv.ecoSupplierGstin,
                eco_supplier_name       = inv.ecoSupplierName,
                eco_recipient_gstin     = inv.ecoRecipientGstin,
                eco_recipient_name      = inv.ecoRecipientName,
                eco_role                = inv.ecoRole,
                document_type           = inv.documentType,
                document_nature         = inv.documentNature,
                document_series         = inv.invoiceNumber.split("_").firstOrNull() ?: inv.documentSeries
            )
        }

        try {
            Log.d(SYNC_TAG, "syncGstInvoices: POST /gst-sales/sync with ${dtoBatch.size} invoice(s)")
            val response = api.syncGstSalesInvoices(
                token,
                GstSalesSyncBatchRequest(dtoBatch)
            )
            Log.d(SYNC_TAG, "syncGstInvoices: server replied success=${response.success_count}, failed=${response.failed_count}")

            response.invoice_id_map.forEach { (localIdStr, serverId) ->
                val localId = localIdStr.toIntOrNull() ?: return@forEach
                db.gstSalesInvoiceDao().markSynced(localId, serverId)
            }
            // Fallback path — server didn't echo a map but accepted
            // the payload (2xx). Flip everything to synced so we
            // don't keep replaying the same rows forever.
            if (response.invoice_id_map.isEmpty()) {
                pending.forEach { db.gstSalesInvoiceDao().markSynced(it.id, null) }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "syncGstInvoices: POST /gst-sales/sync FAILED", e)
            pending.forEach { db.gstSalesInvoiceDao().markFailed(it.id) }
        }
    }

    /**
     * Pushes cancellation state for any GST invoice that was
     * locally cancelled but not yet synced.
     *
     * The sync batch (`syncGstInvoices`) already carries
     * `is_cancelled` and `cancelled_at` in every DTO, so this
     * dedicated method is used only for invoices that were
     * *already* synced before they were cancelled — they won't
     * re-appear in the `pending` queue but still need a backend
     * update.
     */
    suspend fun syncGstCancellations() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: run {
                Log.w(SYNC_TAG, "syncGstCancellations skipped — no auth token")
                return
            }
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        // Find invoices that are cancelled + synced (they have a serverId).
        // These are the rows that the standard syncGstInvoices won't pick up
        // because their sync_status is already 'synced'.
        // We detect them by: is_cancelled=1, sync_status='pending', serverId != null
        val cancelledSynced = db.gstSalesInvoiceDao().getUnsynced()
            .filter { it.isCancelled }

        for (inv in cancelledSynced) {
            try {
                api.cancelGstSalesInvoice(
                    token,
                    GstSalesCancelRequest(
                        invoice_number = inv.invoiceNumber.ifBlank { null },
                        local_id       = inv.id,
                        server_id      = inv.serverId,
                        cancelled_at   = inv.cancelledAt ?: System.currentTimeMillis()
                    )
                )
                Log.d(SYNC_TAG, "syncGstCancellations: cancelled invoice ${inv.id} synced")
                db.gstSalesInvoiceDao().markSynced(inv.id, inv.serverId)
            } catch (e: Exception) {
                Log.e(SYNC_TAG, "syncGstCancellations: failed for invoice ${inv.id}", e)
                // Leave as pending for next retry
            }
        }
    }

    /**
     * Pushes void state for locally-cancelled bills to the server's
     * analytics `bills` table (sets active=false there, so every report
     * excludes them).
     *
     * Covers bills cancelled AFTER they were already synced — bills
     * cancelled BEFORE first sync carry `is_cancelled` inside their
     * CreateBillRequest instead. Also covers non-GST bills, whose
     * cancellation has no GstSalesInvoice row and therefore never
     * travels through syncGstCancellations.
     *
     * N3: each void is pushed until the server acknowledges it once,
     * then `cancel_synced` takes it out of the loop. The endpoint is
     * idempotent, so a lost response retrying next cycle is harmless.
     */
    suspend fun syncBillCancellations() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: run {
                Log.w(SYNC_TAG, "syncBillCancellations skipped — no auth token")
                return
            }
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        // Only bills that already exist on the server; unsynced ones
        // carry the flag in their create payload.
        val cancelled = db.billDao().getCancelledBills().filter { it.isSynced }

        for (bill in cancelled) {
            try {
                api.cancelBill(
                    token,
                    CancelBillRequest(
                        bill_number      = bill.billNumber.takeIf { it.isNotBlank() },
                        client_bill_id   = bill.id,
                        client_device_id = deviceId(),
                        cancelled_at     = bill.cancelledAt ?: System.currentTimeMillis()
                    )
                )
                // N3: acknowledged — drop out of the sync loop for good
                db.billDao().markCancelSynced(bill.id)
                Log.d(SYNC_TAG, "syncBillCancellations: bill ${bill.id} voided on server")
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 404) {
                    // Bill doesn't exist on the server — nothing to cancel.
                    // Mark it done so we don't retry this every sync cycle.
                    db.billDao().markCancelSynced(bill.id)
                    Log.w(SYNC_TAG, "syncBillCancellations: bill ${bill.id} not found on server (404); marked cancel-synced")
                } else {
                    Log.e(SYNC_TAG, "syncBillCancellations: HTTP ${e.code()} for bill ${bill.id}")
                    // Retried automatically on the next sync cycle
                }
            } catch (e: Exception) {
                Log.e(SYNC_TAG, "syncBillCancellations: failed for bill ${bill.id}", e)
                // Retried automatically on the next sync cycle
            }
        }
    }

    // ==========================================
    //  PURCHASE BATCHES, RETURNS & CREDIT NOTES PULL
    // ==========================================

    private fun parseIsoDate(isoString: String?): Long? {
        if (isoString.isNullOrEmpty()) return null
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.time.Instant.parse(isoString.replace("Z", "") + "Z").toEpochMilli()
            } else {
                val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                format.parse(isoString)?.time
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun syncPurchaseBatches() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
        val token = prefs.getString("TOKEN", null)
        if (token == null) {
            Log.w(SYNC_TAG, "syncPurchaseBatches skipped — no auth token")
            return
        }

        val pending = db.purchaseBatchDao().getUnsynced()
        if (pending.isEmpty()) return

        Log.d(SYNC_TAG, "syncPurchaseBatches: ${pending.size} unsynced batch(es)")

        val productDao = db.productDao()

        val dtoBatch = pending.mapNotNull { b: com.example.easy_billing.db.PurchaseBatch ->
            // Resolve local product → server product ID.
            // The server stores product_id as the server-side ID. If we send
            // the local Room ID, pullPurchaseBatches cannot map it back after
            // a factory reset (getByServerId won't find a match).
            val serverProductId = productDao.getById(b.productId)?.serverId
            if (serverProductId == null) {
                Log.w(SYNC_TAG, "syncPurchaseBatches: skipping batch ${b.id} — product ${b.productId} has no serverId yet")
                return@mapNotNull null
            }
            PurchaseBatchDto(
                local_id = b.id,
                product_id = serverProductId,
                purchase_invoice_id = b.purchaseInvoiceId,
                supplier_name = b.supplierName,
                supplier_gstin = b.supplierGstin,
                invoice_number = b.invoiceNumber,
                batch_code = b.batchCode,
                quantity_purchased = b.quantityPurchased,
                quantity_remaining = b.quantityRemaining,
                unit_cost_excluding_tax = b.unitCostExcludingTax,
                gst_percent = b.gstPercent,
                cgst_percent = b.cgstPercent,
                sgst_percent = b.sgstPercent,
                igst_percent = b.igstPercent,
                invoice_value = b.invoiceValue,
                taxable_value = b.taxableValue,
                invoice_date = null,
                created_at = b.createdAt
            )
        }

        try {
            val response = api.syncPurchaseBatches(token, PurchaseBatchSyncRequest(dtoBatch))
            Log.d(SYNC_TAG, "syncPurchaseBatches: server replied success=${response.success_count}")

            db.withTransaction {
                for (dto in dtoBatch) {
                    val serverId = response.batch_id_map[dto.local_id.toString()]
                    if (serverId != null) {
                        db.purchaseBatchDao().markAsSynced(listOf(dto.local_id))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "syncPurchaseBatches: POST FAILED", e)
        }
    }

    suspend fun pullPurchaseBatches() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return
        val shopId = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getInt("SHOP_ID", -1)
        if (shopId == -1) return

        try {
            val batches = api.getPurchaseBatches(token, shopId)
            db.withTransaction {
                for (b in batches) {
                    val existing = db.purchaseBatchDao().getBatchById(b.local_id)
                    if (existing == null) {
                        // Map server product_id → local Room product ID.
                        // Batches on the server carry the server's product_id (e.g. 45).
                        // All local queries use the Room auto-increment id (e.g. 3).
                        // Storing the server id here made every pulled batch invisible to the UI.
                        val localProduct = db.productDao().getByServerId(b.product_id)
                        val localProductId = localProduct?.id
                        if (localProductId == null) {
                            Log.w(SYNC_TAG, "pullPurchaseBatches: skipping batch ${b.local_id} — no local product for serverId=${b.product_id}")
                            continue
                        }
                        db.purchaseBatchDao().insertBatch(
                            com.example.easy_billing.db.PurchaseBatch(
                                id = b.local_id,
                                productId = localProductId,
                                purchaseInvoiceId = b.purchase_invoice_id,
                                supplierName = b.supplier_name,
                                supplierGstin = b.supplier_gstin,
                                invoiceNumber = b.invoice_number,
                                batchCode = b.batch_code,
                                quantityPurchased = b.quantity_purchased,
                                quantityRemaining = b.quantity_remaining,
                                unitCostExcludingTax = b.unit_cost_excluding_tax,
                                gstPercent = b.gst_percent,
                                cgstPercent = b.cgst_percent,
                                sgstPercent = b.sgst_percent,
                                igstPercent = b.igst_percent,
                                invoiceValue = b.invoice_value,
                                taxableValue = b.taxable_value,
                                
                                createdAt = parseIsoDate(b.created_at) ?: System.currentTimeMillis(),
                                isSynced = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "pullPurchaseBatches: FAILED", e)
        }
    }

    suspend fun pullCreditNotes() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return

        try {
            val notes = api.getCreditNotes(token)
            db.withTransaction {
                for (noteDto in notes) {
                    // Try to match by serverId if local_id is missing, or local_id
                    val existing = if (noteDto.local_id != null && noteDto.local_id > 0) {
                        db.creditNoteDao().getById(noteDto.local_id)
                    } else null
                    
                    if (existing == null) {
                        val newId = db.creditNoteDao().insert(
                            com.example.easy_billing.db.CreditNote(
                                id = noteDto.local_id ?: 0,
                                noteNumber = noteDto.note_number,
                                noteDate = parseIsoDate(noteDto.note_date) ?: System.currentTimeMillis(),
                                noteType = noteDto.note_type,
                                noteSupplyType = noteDto.note_supply_type ?: "Regular",
                                originalInvoiceId = noteDto.original_invoice_id ?: 0,
                                originalInvoiceNumber = noteDto.original_invoice_number ?: "",
                                originalInvoiceDate = parseIsoDate(noteDto.original_invoice_date) ?: 0L,
                                customerName = noteDto.customer_name ?: "",
                                customerGstin = noteDto.customer_gstin,
                                placeOfSupply = noteDto.place_of_supply ?: "",
                                reverseCharge = noteDto.reverse_charge,
                                supplyType = noteDto.supply_type,
                                urType = noteDto.ur_type ?: "B2CS",
                                documentType = noteDto.document_type ?: "",
                                documentNature = noteDto.document_nature ?: "",
                                documentSeries = noteDto.document_series ?: "",
                                taxableValue = noteDto.taxable_value,
                                cgstAmount = noteDto.cgst_amount,
                                sgstAmount = noteDto.sgst_amount,
                                igstAmount = noteDto.igst_amount,
                                cessAmount = noteDto.cess_amount,
                                taxAmount = noteDto.tax_amount,
                                totalAmount = noteDto.total_amount,
                                syncStatus = "synced",
                                createdAt = parseIsoDate(noteDto.created_at) ?: System.currentTimeMillis(),
                                updatedAt = parseIsoDate(noteDto.updated_at) ?: System.currentTimeMillis()
                            )
                        )
                        for (itemDto in noteDto.items) {
                            db.creditNoteItemDao().insert(
                                com.example.easy_billing.db.CreditNoteItem(
                                    id = 0,
                                    noteId = newId.toInt(),
                                    productId = itemDto.product_id ?: 0,
                                    productName = itemDto.product_name,
                                    variant = itemDto.variant,
                                    hsnCode = itemDto.hsn_code ?: "",
                                    unit = itemDto.unit ?: "",
                                    quantitySold = itemDto.quantity_sold,
                                    quantityReturned = itemDto.quantity_returned,
                                    rate = itemDto.rate,
                                    costPriceUsed = itemDto.cost_price_used,
                                    taxableValue = itemDto.taxable_value,
                                    gstRate = itemDto.gst_rate,
                                    cgstAmount = itemDto.cgst_amount,
                                    sgstAmount = itemDto.sgst_amount,
                                    igstAmount = itemDto.igst_amount,
                                    cessAmount = itemDto.cess_amount,
                                    taxAmount = itemDto.tax_amount,
                                    totalAmount = itemDto.total_amount,
                                    originalBillItemId = itemDto.original_bill_item_id
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "pullCreditNotes: FAILED", e)
        }
    }

    suspend fun pullPurchaseReturns() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return
        val shopId = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getInt("SHOP_ID", -1)
        if (shopId == -1) return

        try {
            val returns = api.getPurchaseReturns(token, shopId)
            db.withTransaction {
                for (r in returns) {
                    val existingRows = db.purchaseReturnDao().getRecent(10000)
                    val isDuplicate = existingRows.any { it.createdAt == (parseIsoDate(r.created_at) ?: 0L) && it.productId == r.shop_product_id }
                    if (!isDuplicate) {
                        db.purchaseReturnDao().insert(
                            com.example.easy_billing.db.PurchaseReturn(
                                id = 0, // Auto-generate local ID
                                productId = r.shop_product_id,
                                productName = r.product_name,
                                variantName = r.variant_name,
                                hsnCode = r.hsn_code,
                                quantityReturned = r.quantity_returned,
                                taxableAmount = r.taxable_amount,
                                invoiceValue = r.invoice_value,
                                cgstPercentage = r.cgst_percentage,
                                sgstPercentage = r.sgst_percentage,
                                igstPercentage = r.igst_percentage,
                                cgstAmount = r.cgst_amount,
                                sgstAmount = r.sgst_amount,
                                igstAmount = r.igst_amount,
                                state = r.state,
                                supplierGstin = r.supplier_gstin,
                                supplierName = r.supplier_name,
                                isCredit = r.is_credit,
                                creditAccountId = r.credit_account_id,
                                creditTransactionId = null,
                                createdAt = parseIsoDate(r.created_at) ?: System.currentTimeMillis(),
                                isSynced = true,
                                noteNumber = r.note_number,
                                noteDate = parseIsoDate(r.note_date) ?: 0L,
                                noteType = r.note_type,
                                originalInvoiceId = r.original_invoice_id,
                                originalInvoiceNumber = r.original_invoice_number,
                                originalInvoiceDate = parseIsoDate(r.original_invoice_date) ?: 0L,
                                placeOfSupply = r.place_of_supply ?: "",
                                supplyType = r.supply_type ?: "intrastate",
                                cessAmount = r.cess_amount ?: 0.0,
                                documentType = r.document_type ?: "Debit Note",
                                documentNature = r.document_nature,
                                documentSeries = r.document_series,
                                preGst = r.pre_gst ?: "N",
                                reasonForIssuingDocument = r.reason_for_issuing_document ?: "Purchase return",
                                noteRefundVoucherValue = r.note_refund_voucher_value ?: 0.0,
                                rate = r.rate ?: 0.0,
                                eligibilityForItc = r.eligibility_for_itc ?: "Inputs",
                                availedItcIntegratedTax = r.availed_itc_integrated_tax ?: 0.0,
                                availedItcCentralTax = r.availed_itc_central_tax ?: 0.0,
                                availedItcStateTax = r.availed_itc_state_tax ?: 0.0,
                                availedItcCess = r.availed_itc_cess ?: 0.0,
                                invoiceType = r.invoice_type ?: "Regular",
                                placeOfSupplyCode = r.place_of_supply_code ?: ""
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "pullPurchaseReturns: FAILED", e)
        }
    }

}
