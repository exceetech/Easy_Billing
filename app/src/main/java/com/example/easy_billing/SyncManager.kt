package com.example.easy_billing.sync

import com.example.easy_billing.util.appNow

import android.content.Context
import androidx.room.withTransaction
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.InventoryValuation
import com.example.easy_billing.db.BillingSettings
import com.example.easy_billing.db.CreditAccount
import com.example.easy_billing.db.Supplier
import com.example.easy_billing.db.GstProfile
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
import com.example.easy_billing.network.GstSaleRecordDto
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
import com.example.easy_billing.network.SupplierDto
import com.example.easy_billing.network.SupplierSyncRequest

class SyncManager(private val context: Context) {


    suspend fun syncAll() = syncAllMutex.withLock {

        // Serialize across every entry point so two full syncs can't run
        // concurrently and double-push non-idempotent rows (Issue 5).

        // 🔥 ORDER MATTERS — products before anything that references
        // them (purchases, bills, returns, scrap), pulls last.

        syncAccounts()           // account first
        syncCredit()             // then transactions
        syncCategories()         // custom categories (string-on-product, no FK)
        syncShopProducts()       // push unsynced shop_product (and global)
        syncCustomers()          // customer master (after products, before invoices)
        syncSuppliers()          // supplier master (autofill index; no FK)
        syncInventory()          // inventory BEFORE bills
        syncBills()              // then bills
        syncPurchases()
        syncPurchaseBatches()
        syncPurchaseImportDetails()
        syncImportServices()     // push import services
        syncPurchaseReturns()    // push purchase returns (debit notes)
        syncCreditNotes()        // push sales returns (credit notes)
        syncScrapEntries()       // push scrap
        syncGstProfile()
        syncGstSales()
        syncGstInvoices()        // push GST-aware invoice batch
        syncGstCancellations()   // push pending GST invoice cancellations
        syncBillCancellations()  // push voided bills to the analytics table
        syncStoreInfo()          // pull latest
        syncBillingSettings()    // pull settings

        // Recompute the observable sync status so any open screen can
        // reflect what is still pending / failed / blocked (Issue 11).
        refreshSyncStatus()
    }

    /**
     * Recomputes the process-wide [SyncState] from a handful of cheap
     * COUNT(*) queries. Call at the end of a sync pass. Never throws —
     * status reporting must never break the actual sync.
     *
     *   • pending          — rows still waiting to upload (normal/transient).
     *   • failed           — rows that errored and are being retried.
     *   • blockedByProduct — products not yet uploaded; every bill / log /
     *                        purchase referencing them is stuck behind them.
     */
    suspend fun refreshSyncStatus() {
        runCatching {
            val db = AppDatabase.getDatabase(context)

            val pending =
                db.billDao().countUnsynced() +
                db.purchaseDao().countUnsynced() +
                db.inventoryLogDao().countUnsynced() +
                db.gstSalesInvoiceDao().countPending() +
                db.creditNoteDao().countPending() +
                // R2: these batch-push tables were omitted, so status could read
                // "all synced" while they were still pending.
                db.purchaseReturnDao().countUnsynced() +
                db.scrapDao().countUnsynced() +
                db.importServiceDao().countPending()

            val failed =
                db.gstSalesInvoiceDao().countFailed() +
                db.creditNoteDao().countFailed() +
                // Records the server refused for breaking a GSTR-2 rule. They
                // belong in "failed", not "pending": they are not queued and
                // will never sync on their own — someone has to correct them.
                // Left out of both counts, the app reported "all synced" while
                // holding records that had never reached the server, which is
                // the same hole the R2 note above closed for the batch tables.
                db.importServiceDao().countRejected()

            val blocked = db.productDao().countUnsynced()

            val now  = System.currentTimeMillis()
            val prev = SyncState.current
            SyncState.update(
                prev.copy(
                    pending          = pending,
                    failed           = failed,
                    blockedByProduct = blocked,
                    lastRunAt        = now,
                    lastSuccessAt    = if (failed == 0 && blocked == 0) now else prev.lastSuccessAt
                )
            )
        }.onFailure { Log.w(SYNC_TAG, "refreshSyncStatus failed (non-fatal): ${it.message}") }
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
                        supply_classification = product.supplyClassification ?: "TAXABLE",
                        category         = product.category,
                        is_purchased     = product.isPurchased,
                        is_tax_inclusive = product.isTaxInclusive
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
                            name             = product.name,
                            variant          = product.variant,
                            unit             = product.unit,
                            hsn_code         = product.hsnCode,
                            hsn_description  = product.hsnDescription,
                            official_uqc     = product.officialUqc,
                            default_gst_rate = product.defaultGstRate,
                            cgst_percentage  = product.cgstPercentage,
                            sgst_percentage  = product.sgstPercentage,
                            igst_percentage  = product.igstPercentage,
                            cess_rate        = product.cessRate
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

    /**
     * Pushes locally-created/edited suppliers.
     *
     * Unlike customers this pushes on `isSynced = 0` rather than a null
     * serverId, because suppliers get *edited* — a rename or corrected
     * state on an already-synced row still has to go up.
     *
     * The server upserts by (shop_id, gstin), so two devices that recorded
     * purchases from the same GSTIN offline converge to one row. No FK
     * dependency in either direction: purchases carry their own supplier
     * snapshot, so this table is pure convenience and failures here are
     * harmless.
     *
     * `updated_at` is the supplier's own `updatedAt`, which moves only when
     * a *detail* changes (name / GSTIN / state) — not on every purchase.
     * It used to be sent as `lastUsedAt`, so the server's newest-wins rule
     * was really comparing recency of use: a rename on a device that then
     * sat idle lost to a device that merely bought from the supplier again,
     * and the rename was silently dropped.
     */
    suspend fun syncSuppliers() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: run {
                Log.w(SYNC_TAG, "syncSuppliers skipped — no auth token")
                return
            }
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        // Shop-scoped: the server files whatever arrives under the shop in the
        // token, so an unscoped query could push another shop's suppliers into
        // this one. Safe today only because the local database is wiped on a
        // shop change — not a guarantee worth relying on.
        val shopId = currentShopIdOrNull() ?: run {
            Log.w(SYNC_TAG, "syncSuppliers skipped — no valid SHOP_ID")
            return
        }

        val pending = db.supplierDao().getUnsynced(shopId)
        if (pending.isEmpty()) return
        Log.d(SYNC_TAG, "syncSuppliers: ${pending.size} unsynced supplier(s)")

        try {
            val dtos = pending.map {
                SupplierDto(
                    local_id     = it.id,
                    name         = it.name,
                    gstin        = it.gstin,
                    state        = it.state,
                    state_code   = GstEngine.getStateCodeFromName(it.state),
                    last_used_at = it.lastUsedAt,
                    updated_at   = it.updatedAt
                )
            }
            val response = api.syncSuppliers(token, SupplierSyncRequest(dtos))
            // Only rows the server acknowledged are marked synced; anything
            // it skipped stays pending and is retried on the next run.
            response.supplier_id_map.forEach { (localIdStr, serverId) ->
                localIdStr.toIntOrNull()?.let { db.supplierDao().markSynced(it, serverId) }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "syncSuppliers push failed", e)
        }
    }

    /**
     * Seeds / refreshes the supplier picker from the server.
     *
     * Matters most on a fresh install: without it the picker is empty
     * until the device has recorded purchases of its own, even though the
     * shop has years of suppliers on other terminals.
     *
     * Matching order per remote row — serverId, then GSTIN, then name
     * among unregistered rows. Going straight to an insert would violate
     * the unique (shopId, gstin) index for a supplier this device already
     * knows locally.
     *
     * A local row with pending edits (`isSynced = 0`) is left alone: push
     * runs before pull, but if that push failed the user's own edit is
     * still the newer truth and must not be overwritten by what the server
     * had before it.
     */
    suspend fun pullSuppliers() {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return
        val shopId = currentShopIdOrNull() ?: run {
            Log.w(SYNC_TAG, "pullSuppliers skipped — no valid SHOP_ID")
            return
        }
        val db = AppDatabase.getDatabase(context)
        val dao = db.supplierDao()

        try {
            val remote = RetrofitClient.api.getSuppliers(token).suppliers
            Log.d(SYNC_TAG, "pullSuppliers: ${remote.size} supplier(s) from server")

            for (r in remote) {
              // Per-row: a single conflicting insert must not abandon the
              // rest of the pull.
              try {
                val name = r.name.trim()
                if (name.isEmpty()) continue
                val gstin = r.gstin?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                val key = Supplier.keyOf(name)

                val existing = dao.getByServerId(r.id, shopId)
                    ?: gstin?.let { dao.getByGstinAny(it, shopId) }
                    ?: dao.getUnregisteredByName(key, shopId).singleOrNull()

                if (existing != null) {
                    // Don't clobber an edit that hasn't made it up yet.
                    if (!existing.isSynced) continue
                    dao.update(
                        existing.copy(
                            serverId   = r.id,
                            name       = name,
                            nameKey    = key,
                            gstin      = gstin,
                            state      = r.state?.takeIf { it.isNotBlank() } ?: existing.state,
                            // Recency is a max, not a copy: this device may
                            // have used the supplier more recently offline.
                            lastUsedAt = maxOf(existing.lastUsedAt, r.last_used_at),
                            // The row now holds the server's details, so it
                            // must also carry the server's modification time.
                            // Keeping the local one would make an already-
                            // overwritten row look newer than what overwrote
                            // it, and the next push would send stale details
                            // back up as if they were an edit.
                            updatedAt  = maxOf(existing.updatedAt, r.updated_at),
                            isSynced   = true,
                            isActive   = true
                        )
                    )
                } else {
                    dao.insert(
                        Supplier(
                            serverId   = r.id,
                            gstin      = gstin,
                            name       = name,
                            nameKey    = key,
                            state      = r.state.orEmpty(),
                            lastUsedAt = r.last_used_at,
                            // Older servers don't send updated_at; fall back
                            // to last_used_at rather than "now", which would
                            // make every freshly pulled row outrank real
                            // edits sitting on other devices.
                            updatedAt  = if (r.updated_at > 0) r.updated_at else r.last_used_at,
                            isSynced   = true,
                            shopId     = shopId
                        )
                    )
                }
              } catch (rowError: Exception) {
                Log.w(SYNC_TAG, "pullSuppliers: skipped supplier ${r.id}", rowError)
              }
            }
        } catch (e: Exception) {
            // The picker is a convenience: a failed pull just means it
            // shows what this device already knows.
            Log.e(SYNC_TAG, "pullSuppliers failed", e)
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
     * The current numeric shop id, or null when none is set.
     *
     * Use this instead of `getInt("SHOP_ID", 1)` — defaulting to 1 silently
     * attributes a signed-out / freshly-wiped session's rows to shop #1, which
     * may be a different business (Issue 8). A null result means "no shop yet",
     * and sync methods should skip rather than guess.
     */
    private fun currentShopIdOrNull(): Int? =
        context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getInt("SHOP_ID", -1)
            .takeIf { it > 0 }

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
    suspend fun pushPurchaseImmediately(purchaseId: Int): SyncResult = syncAllMutex.withLock {
        // Serialize with syncAll() (Sync deep-audit H1). This inline push calls
        // syncAccounts()/syncCredit()/syncShopProducts(), none of which are
        // individually mutexed; without this lock it can run concurrently with a
        // background syncAll() (5-min retry, network-regain, dashboard resume) and
        // double-push non-idempotent rows — duplicate credit accounts via
        // createCreditAccount and duplicate credit transactions via syncCredit.
        // Lock order is identical to syncAll (syncAllMutex first), so no deadlock;
        // we never acquire it while holding billSyncMutex.
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return@withLock SyncResult.Skipped("not signed in")
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
            ?: return@withLock SyncResult.Skipped("local purchase not found")
        if (purchase.isSynced) return@withLock SyncResult.NothingToDo

        val mainResult = pushPurchases(token, listOf(purchase))

        // Also push the inventory logs created by this purchase immediately.
        runCatching { syncInventory() }.onFailure {
            Log.w(SYNC_TAG, "pushPurchaseImmediately: inventory logs sync failed: ${it.message}")
        }

        mainResult
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
                    discount_amount          = item.discountAmount,
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
            val shopId = currentShopIdOrNull()
            val serverCreditAccountId = if (shopId != null) {
                p.creditAccountId?.let { localId ->
                    db.creditAccountDao().getById(localId, shopId)?.serverId
                }
            } else null

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
            // Only mark synced the purchases the server actually confirmed in
            // its local_id → server_id map. Rows missing from the map keep their
            // pending flag and retry next pass. We must NOT blanket-mark on an
            // empty map: that would (a) drop rows the server silently rejected
            // and (b) leave the purchase with serverId = null, so its child
            // records (import details, batches) could never resolve their parent.
            // The endpoint is idempotent on (shop_id, local_id), so retrying a
            // row that did land server-side is harmless.
            response.purchase_id_map.forEach { (localIdStr, serverId) ->
                val localId = localIdStr.toIntOrNull() ?: return@forEach
                db.purchaseDao().markSynced(localId, serverId)
                db.purchaseItemDao().markAllSyncedForPurchase(localId)
            }
            if (response.purchase_id_map.isEmpty()) {
                Log.w(SYNC_TAG, "pushPurchases: 2xx but empty id_map — leaving " +
                    "${pending.size} purchase(s) pending for retry")
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

        /**
         * Serializes [syncAll] across ALL entry points (SyncCoordinator,
         * NetworkReceiver, post-login MainActivity). Without it two full
         * syncs can overlap and double-push non-idempotent rows (Issue 5).
         * Lock order is always syncAllMutex → billSyncMutex (syncBills runs
         * inside syncAll), never the reverse, so there is no deadlock.
         */
        val syncAllMutex = kotlinx.coroutines.sync.Mutex()
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
            // Mark synced ONLY the records the server echoed as accepted (M1).
            // The old "empty map → mark all" fallback silently dropped any row the
            // server rejected. Unmapped rows stay pending and retry next pass; the
            // backend upserts by (shop_id, local_id) so re-push is safe.
            response.record_id_map.forEach { (localIdStr, _) ->
                val localId = localIdStr.toIntOrNull() ?: return@forEach
                db.purchaseImportDetailsDao().markSynced(localId)
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

        // Guard only: the server derives the shop from the token now, but
        // there is no point pushing while no shop is selected.
        if (currentShopIdOrNull() == null) {
            Log.w(SYNC_TAG, "syncImportServices skipped — no valid SHOP_ID")
            return
        }

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
            val response = api.syncImportServices(token, dtos)
            if (response.isSuccessful) {
                val body = response.body()
                val accepted = body?.accepted_local_ids
                val rejected = body?.rejected.orEmpty()

                // Park anything the server refused for breaking a GSTR-2 rule.
                // These are skipped server-side, not stored, so leaving them
                // 'pending' would resend them on every sync for ever.
                val rejectedSet = rejected.mapNotNull { it.local_id }.toHashSet()
                rejected.forEach {
                    Log.w(SYNC_TAG, "syncImportServices: rejected invoice ${it.invoice_number} — ${it.reason}")
                }
                pending.forEach { if (it.id in rejectedSet) db.importServiceDao().markAsRejected(it.id) }

                // Mark synced ONLY the local_ids the server acknowledged (M1).
                // If the server omits the field (older build), fall back to the
                // previous behaviour and mark the whole batch — that backend is
                // all-or-nothing, so a 200 means everything persisted. The
                // rejected check matters here: a batch where every record was
                // refused also comes back with an empty accepted list, and the
                // fallback would then mark the whole thing synced.
                if (accepted != null && accepted.isNotEmpty()) {
                    val acceptedSet = accepted.toHashSet()
                    pending.forEach { if (it.id in acceptedSet) db.importServiceDao().markAsSynced(it.id) }
                } else if (rejected.isEmpty()) {
                    pending.forEach { db.importServiceDao().markAsSynced(it.id) }
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
            val serverRecords = api.getImportServices(token)
            val dao = db.importServiceDao()

            db.withTransaction {
                // Match on the natural key (invoice_number, invoice_date) — NOT the
                // originating device's local_id. Reusing that id as our primary key
                // collided with this device's own autoincrement rows, so a mirrored
                // record was either skipped or overwrote an unrelated local row (H2).
                // Insert with id=0 so Room assigns a fresh local primary key.
                val existingByKey = dao.getAllImportServices()
                    .associateBy { it.invoiceNumber to it.invoiceDate }
                for (record in serverRecords) {
                    val existing = existingByKey[record.invoice_number to record.invoice_date]
                    if (existing != null) {
                        // PROTECT LOCAL CHANGES — same guard pullInventory uses.
                        // A row that is not 'synced' has edits this device has
                        // not pushed yet. Overwriting it would replace the
                        // user's correction with the server's older figures and
                        // then mark it green, so the fix would vanish silently.
                        // It stays untouched; the next push sends it up, and the
                        // pull after that brings back the same values anyway.
                        if (existing.syncStatus != "synced") {
                            Log.d(SYNC_TAG,
                                "⏳ Skipping pull for import service ${existing.invoiceNumber} → local has unsynced changes")
                            continue
                        }
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
                                id = 0,
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
            val shopId = currentShopIdOrNull()
            val serverCreditAccountId = if (shopId != null) {
                r.creditAccountId?.let { localId ->
                    db.creditAccountDao().getById(localId, shopId)?.serverId
                }
            } else null

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
            val response = api.syncPurchaseReturns(token, PurchaseReturnSyncRequest(dtos))
            // Mark synced ONLY the records the server accepted (echoed in
            // record_id_map). Rejected ones stay pending and retry next pass —
            // never blanket-mark the batch, which silently lost them (R1). The
            // backend dedupes on (shop_id, local_id) so re-push is safe.
            val accepted = response.record_id_map.keys.mapNotNull { it.toIntOrNull() }.toSet()
            pending.forEach { if (it.id in accepted) db.purchaseReturnDao().markSynced(it.id) }
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

            // Mark synced ONLY the notes the server echoed as accepted. The old
            // "empty map → mark all synced" fallback silently lost notes the
            // server rejected (R1). Unmapped rows stay pending and retry next
            // pass; the backend dedupes on (shop_id, local_id) so re-push is safe.
            response.note_id_map.forEach { (localIdStr, _) ->
                val localId = localIdStr.toIntOrNull() ?: return@forEach
                db.creditNoteDao().markSynced(localId)
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
            val response = api.syncScrap(token, ScrapSyncRequest(dtos))
            // Mark synced ONLY accepted records (R1); rejected ones retry next pass.
            val accepted = response.record_id_map.keys.mapNotNull { it.toIntOrNull() }.toSet()
            pending.forEach { if (it.id in accepted) db.scrapDao().markSynced(it.id) }
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
                        // This bill can't be pushed until its product uploads.
                        // Surfaced to the user via SyncState.blockedByProduct (Issue 4).
                        Log.w(SYNC_TAG, "syncBills: bill ${bill.id} blocked — product " +
                            "'${product?.name}' has no serverId yet")
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
                        discount_amount = it.discountAmount,
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
                    Log.w(SYNC_TAG, "syncBills: bill ${bill.id} skipped this pass — " +
                        "${items.size - apiItems.size} item(s) reference an un-uploaded product; " +
                        "will retry once products sync")
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
                    
                    // subtotal = GROSS (pre-discount) from the bill row;
                    // taxable_amount = NET taxable (GST base) from the GST invoice.
                    subtotal = bill.subTotal,
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

            // ── PUSH FIRST (Issue 15) ──────────────────────────────────────
            // If there's an un-pushed local edit (e.g. the user changed the
            // store profile while offline), upload it and mark it synced. We
            // must NOT pull-and-overwrite first, or the local edit is lost.
            val localPending = db.storeInfoDao().getUnsynced()
            if (localPending != null) {
                api.updateStoreSettings(
                    token,
                    com.example.easy_billing.network.ShopSettingsUpdateRequest(
                        localPending.name,
                        localPending.address,
                        localPending.phone,
                        localPending.gstin,
                        localPending.type
                    )
                )
                db.storeInfoDao().markSynced()
                // Local is now the source of truth this pass; skip the pull so
                // we don't clobber the just-pushed values with a stale read.
                return
            }

            // ── PULL (only when local is clean) ────────────────────────────
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
            // Offline or transient error → leave any local edit pending so it
            // retries next pass. Never overwrite local on failure.
            Log.w(SYNC_TAG, "syncStoreInfo: ${e.message}")
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
        val shopId = currentShopIdOrNull() ?: run {
            Log.w(SYNC_TAG, "syncCredit skipped — no valid SHOP_ID")
            return
        }

        // Oldest first — see CreditTransactionDao.getUnsynced.
        val txns = db.creditTransactionDao().getUnsynced(shopId)

        // Accounts whose queue has stalled this pass. Once one transaction for
        // an account fails, the rest of that account's queue must wait: sending
        // them anyway replays this account's history out of order, and SETTLE
        // zeroes the balance rather than adjusting it. A sale that failed and
        // retried after a settle went through would put the debt back on an
        // account that had been cleared.
        //
        // Scoped per account so one customer's problem doesn't hold up
        // everyone else's payments.
        val stalledAccounts = mutableSetOf<Int>()

        for (txn in txns) {

            if (txn.accountId in stalledAccounts) {
                Log.d(SYNC_TAG, "⏸ Holding txnId=${txn.id} — earlier transaction for this account is still unsent")
                continue
            }

            try {

                // ✅ FIX: pass shopId
                val account = db.creditAccountDao().getById(txn.accountId, shopId)

                if (account?.serverId == null || account.serverId == -1) {
                    Log.d(SYNC_TAG, "⏳ Waiting for account sync → txnId=${txn.id}")
                    stalledAccounts.add(txn.accountId)
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
                    Log.d(SYNC_TAG, "❌ ERROR: ${res.code()} ${res.errorBody()?.string()}")
                    stalledAccounts.add(txn.accountId)
                    continue
                }

            } catch (e: Exception) {
                e.printStackTrace()
                stalledAccounts.add(txn.accountId)
                continue
            }
        }
    }

    suspend fun syncAccounts() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

        val token = prefs.getString("TOKEN", null) ?: return
        val shopId = currentShopIdOrNull() ?: run {
            Log.w(SYNC_TAG, "syncAccounts skipped — no valid SHOP_ID")
            return
        }

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

                // The server answers 400 "Account already exists" when an
                // ACTIVE account already holds this phone — which happens when
                // the account was created offline here while another terminal
                // already had it. Treated as a plain failure, this row retried
                // on every sync for ever and never got a serverId, so
                // syncCredit skipped all its transactions too: the customer's
                // payments never left this device, silently.
                //
                // That 400 is really the server saying "it's already here", so
                // adopt its account instead of failing.
                val adopted = runCatching {
                    api.getCreditAccounts(token)
                        .firstOrNull { it.phone == acc.phone }
                }.getOrNull()

                if (adopted != null) {
                    Log.d(SYNC_TAG,
                        "🔗 Adopting existing server account ${adopted.id} for local ${acc.id} (${acc.phone})")
                    db.creditAccountDao().updateServerId(acc.id, adopted.id, shopId)
                } else {
                    e.printStackTrace()
                }
                continue
            }
        }
    }

    suspend fun pullAccountsFromServer() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

        val token = prefs.getString("TOKEN", null) ?: return
        val shopId = currentShopIdOrNull() ?: run {
            Log.w(SYNC_TAG, "pullAccountsFromServer skipped — no valid SHOP_ID")
            return
        }

        try {

            val accounts = api.getCreditAccounts(token)

            // Propagate deletions (Sync audit S6): the server only returns ACTIVE
            // accounts, so any local server-known account missing from this set was
            // deactivated on another device → deactivate it locally too. Guarded on
            // non-empty (Room can't bind an empty IN-list; also avoids mass-hiding
            // on a spurious empty response — the last-account case reconciles once
            // any active account exists).
            val activeServerIds = accounts.map { it.id }
            if (activeServerIds.isNotEmpty()) {
                db.creditAccountDao().deactivateMissing(shopId, activeServerIds)
            }

            for (acc in accounts) {

                val existing = db.creditAccountDao()
                    .getByServerId(acc.id, shopId)

                if (existing != null) {
                    // PROTECT LOCAL CHANGES — a payment taken offline is already
                    // in the local balance but not in the server's, so taking
                    // the server figure would silently undo it. Same guard as
                    // pullInventory and pullImportServices.
                    val unsent = db.creditTransactionDao()
                        .countUnsyncedForAccount(existing.id, shopId)

                    if (unsent > 0) {
                        Log.d(SYNC_TAG,
                            "⏳ Skipping pull for credit account ${existing.id} → $unsent unsent transaction(s)")
                    } else {
                        // update(), not insert(): insert is IGNORE-on-conflict and
                        // this row already exists, so it did nothing and a balance
                        // changed on another terminal never arrived here.
                        db.creditAccountDao().update(
                            existing.copy(
                                name = acc.name,
                                phone = acc.phone,
                                dueAmount = acc.due_amount,
                                isActive = true
                            )
                        )
                    }
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
                Log.d(SYNC_TAG, "⏳ Skipping log ${log.id} → product not synced yet")
                continue
            }

            validRequests.add(
                InventoryLogRequest(
                    product_id = product.serverId,
                    type = log.type,
                    quantity = log.quantity,
                    price = log.price,
                    date = log.date,  // 🔥 REQUIRED
                    // Stable key so a retried push dedupes exactly (Sync audit S2).
                    client_uid = "${deviceId()}:${log.id}"
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

                Log.d(SYNC_TAG, "✅ Inventory synced: ${syncedLogIds.size}")
            } else {

                Log.d(SYNC_TAG, "❌ Sync failed: ${response.code()}")
            }

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }

    /**
     * @param retryMissingProducts when true (default), a single extra pass is
     *   allowed after fetching products that the server's inventory referenced
     *   but we didn't have locally. The recursive call passes `false` so an
     *   orphaned server inventory row (product the server can't actually return)
     *   can't drive infinite recursion / server hammering (Issue 10).
     */
    suspend fun pullInventory(retryMissingProducts: Boolean = true) {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        // Delta cursor (Sync audit S5): server-set updated_at, keyed by shop.
        val cursorPrefs = context.getSharedPreferences("sync_cursors", Context.MODE_PRIVATE)
        val shopId = currentShopIdOrNull() ?: -1
        val cursorKey = "inventory_updated_since_$shopId"
        val since = cursorPrefs.getLong(cursorKey, 0L)

        try {

            val serverInventory = api.getInventory(token, since)

            Log.d(SYNC_TAG, "📦 SERVER INVENTORY (since=$since) SIZE = ${serverInventory.size}")

            val productDao = db.productDao()
            val inventoryDao = db.inventoryDao()

            // 🔥 Load products ONCE
            val products = productDao.getAll()
            val productMap = products.associateBy { it.serverId }

            val missingProductIds = mutableSetOf<Int>()
            var maxUpdatedAt = since

            db.withTransaction {

                for (item in serverInventory) {

                    item.updated_at?.let { if (it > maxUpdatedAt) maxUpdatedAt = it }

                    Log.d(SYNC_TAG, "➡️ Processing product_id=${item.product_id}, stock=${item.stock}")

                    // 🔥 TRY TO FIND PRODUCT
                    val product = productMap[item.product_id]
                        ?: productDao.getByServerId(item.product_id)

                    if (product == null) {
                        Log.d(SYNC_TAG, "⚠️ Product missing locally: ${item.product_id}")
                        missingProductIds.add(item.product_id)
                        continue
                    }

                    val existing = inventoryDao.getInventoryIncludingInactive(product.id)

                    if (existing != null) {
                        // 🔥 PROTECT LOCAL CHANGES
                        if (!existing.isSynced) {
                            Log.d(SYNC_TAG, "⏳ Skipping pull for productId=${product.id} → local has unsynced changes")
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
                        Log.d(SYNC_TAG, "✅ Updated inventory for productId=${product.id}")
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

                        Log.d(SYNC_TAG, "✅ Inserted inventory for productId=${product.id}")
                    }
                }
            }

            // 🔥 FIX MISSING PRODUCTS (CRITICAL FOR REINSTALL CASE)
            // Only when retryMissingProducts is true, so a server inventory row
            // pointing at a product the server can't return won't loop forever.
            if (missingProductIds.isNotEmpty() && retryMissingProducts) {

                Log.d(SYNC_TAG, "🔄 Missing products detected → refetching products")

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
                                        category       = bp.category.ifBlank { alreadyLocal.category },
                                        isTaxInclusive = bp.is_tax_inclusive
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
                                        category       = bp.category,
                                        isTaxInclusive = bp.is_tax_inclusive
                                    )
                                )
                            }

                            Log.d(SYNC_TAG, "✅ Recovered missing product ${bp.id}")
                        }
                    }

                    // 🔥 RE-RUN INVENTORY SYNC ONCE MORE (and only once):
                    // pass retryMissingProducts = false to cap the recursion.
                    Log.d(SYNC_TAG, "🔁 Re-running inventory sync after product fix")
                    pullInventory(retryMissingProducts = false)

                } catch (e: Exception) {
                    Log.d(SYNC_TAG, "❌ Failed to refetch missing products: ${e.message}")
                }
            } else if (missingProductIds.isNotEmpty()) {
                Log.w(SYNC_TAG, "pullInventory: ${missingProductIds.size} inventory row(s) " +
                    "reference unknown product(s) $missingProductIds — skipped to avoid retry loop")
            }

            // Advance the delta cursor ONLY on a clean outer pass with no missing
            // products. If anything was skipped we keep the old cursor so those
            // rows are re-pulled next time — worst case a harmless re-fetch (the
            // upsert dedupes), never a silently-missed row. The recursive
            // retryMissingProducts=false call never advances the cursor.
            if (retryMissingProducts && missingProductIds.isEmpty() && maxUpdatedAt > since) {
                cursorPrefs.edit().putLong(cursorKey, maxUpdatedAt).apply()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun pullPurchases() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return

        // Delta cursor (Sync audit S5): server-set updated_at, keyed by shop.
        val cursorPrefs = context.getSharedPreferences("sync_cursors", Context.MODE_PRIVATE)
        val pShopId = currentShopIdOrNull() ?: -1
        val cursorKey = "purchases_updated_since_$pShopId"
        val since = cursorPrefs.getLong(cursorKey, 0L)

        try {
            val serverPurchases = api.getPurchases(token, since)
            Log.d(SYNC_TAG, "📦 SERVER PURCHASES (since=$since) SIZE = ${serverPurchases.size}")

            val productDao = db.productDao()
            val purchaseDao = db.purchaseDao()
            val purchaseItemDao = db.purchaseItemDao()

            val products = productDao.getAll()
            val productMap = products.associateBy { it.serverId }

            var maxUpdatedAt = since

            db.withTransaction {
                for (p in serverPurchases) {
                    p.updated_at?.let { if (it > maxUpdatedAt) maxUpdatedAt = it }
                    // Check if purchase already exists locally by server ID
                    val existing = purchaseDao.getByServerId(p.id)
                    if (existing != null) {
                        Log.d(SYNC_TAG, "⏳ Skipping pull for purchase serverId=${p.id} → already exists")
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
                                discountAmount = item.discount_amount,
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

            // Advance the delta cursor. Purchases are inserted-once (existing
            // server ids are skipped), so there's no data-losing skip path —
            // safe to advance to the newest updated_at we saw.
            if (maxUpdatedAt > since) {
                cursorPrefs.edit().putLong(cursorKey, maxUpdatedAt).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun pullInventoryLogs() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return

        // Delta cursor (Sync audit S5): only fetch logs newer than the last one
        // we pulled, keyed by shop so a workspace change starts fresh. Stored in
        // prefs (no local schema change); the server filters on the monotonic id.
        val cursorPrefs = context.getSharedPreferences("sync_cursors", Context.MODE_PRIVATE)
        val shopId = currentShopIdOrNull() ?: -1
        val cursorKey = "inv_logs_after_id_$shopId"
        val afterId = cursorPrefs.getInt(cursorKey, 0)

        try {
            val serverLogs = api.getInventoryLogs(token, afterId)
            Log.d(SYNC_TAG, "📦 SERVER INVENTORY LOGS (after id=$afterId) SIZE = ${serverLogs.size}")

            val productDao = db.productDao()
            val inventoryLogDao = db.inventoryLogDao()

            val products = productDao.getAll()
            val productMap = products.associateBy { it.serverId }

            var maxProcessedId = afterId
            // Smallest id we had to skip because its product isn't local yet.
            // We never advance the cursor past it, so it's re-pulled once the
            // product arrives (pullInventory runs before this and recovers them).
            var firstSkippedId = Int.MAX_VALUE

            db.withTransaction {
                for (item in serverLogs) {
                    val product = productMap[item.product_id] ?: productDao.getByServerId(item.product_id)
                    if (product == null) {
                        if (item.id in 1 until firstSkippedId) firstSkippedId = item.id
                        continue
                    }

                    // Content-dedup safety net (covers the first/overlapping pull).
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
                                date = item.date ?: appNow(),
                                isSynced = true
                            )
                        )
                    }
                    if (item.id > maxProcessedId) maxProcessedId = item.id
                }
            }

            // Advance the cursor — but stop just before any skipped log so it is
            // re-fetched next pass. If nothing was skipped, advance to the max id.
            val newCursor = if (firstSkippedId != Int.MAX_VALUE)
                minOf(maxProcessedId, firstSkippedId - 1)
            else
                maxProcessedId
            if (newCursor > afterId) {
                cursorPrefs.edit().putInt(cursorKey, newCursor).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Mirror bills created on OTHER terminals into local Room (Sync re-audit R3),
     * so this device can reprint them and run accurate (tax-complete) sales
     * returns / debit notes against them. Append-only `id` cursor, keyed by shop.
     *
     * Safety:
     *  • Dedupe by bill_number — never duplicates a bill we created/pulled.
     *  • If any item's product isn't local yet, skip that bill and HOLD the cursor
     *    before it (re-pulled once the product arrives; pullInventory recovers
     *    products earlier in the cascade). Never inserts a partial bill.
     */
    suspend fun pullBills() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        val cursorPrefs = context.getSharedPreferences("sync_cursors", Context.MODE_PRIVATE)
        val shopId = currentShopIdOrNull() ?: -1
        val cursorKey = "bills_after_id_$shopId"
        val afterId = cursorPrefs.getInt(cursorKey, 0)

        try {
            val serverBills = api.getBillsSince(token, afterId)
            Log.d(SYNC_TAG, "pullBills: ${serverBills.size} bill(s) after id=$afterId")

            val billDao = db.billDao()
            val productDao = db.productDao()

            var maxProcessedId = afterId
            var firstSkippedId = Int.MAX_VALUE

            for (sb in serverBills) {
                // Already present (created here, or pulled before) → advance past it.
                if (sb.bill_number.isBlank() || billDao.getByBillNumber(sb.bill_number) != null) {
                    if (sb.bill_id > maxProcessedId) maxProcessedId = sb.bill_id
                    continue
                }

                // Map every item's product → local id; if any is missing, defer.
                val mapped = sb.items.map { it to productDao.getByServerId(it.shop_product_id)?.id }
                if (mapped.any { it.second == null }) {
                    if (sb.bill_id < firstSkippedId) firstSkippedId = sb.bill_id
                    continue
                }

                db.withTransaction {
                    val localBillId = billDao.insertBill(
                        com.example.easy_billing.db.Bill(
                            id = 0,
                            billNumber = sb.bill_number,
                            date = sb.created_at ?: "",
                            subTotal = sb.subtotal,
                            gst = sb.gst_amount,
                            discount = sb.discount_amount,
                            total = sb.final_amount,
                            paymentMethod = sb.payment_method,
                            customerType = sb.invoice_type,
                            placeOfSupply = sb.customer_state ?: "",
                            supplyType = sb.supply_type,
                            cgstAmount = sb.cgst_amount,
                            sgstAmount = sb.sgst_amount,
                            igstAmount = sb.igst_amount,
                            isSynced = true,
                            isCancelled = sb.is_cancelled,
                            cancelledAt = sb.cancelled_at,
                            cancelSynced = true
                        )
                    ).toInt()

                    billDao.insertItems(
                        mapped.map { (it, pid) ->
                            com.example.easy_billing.db.BillItem(
                                id = 0,
                                billId = localBillId,
                                productId = pid!!,
                                productName = it.product_name,
                                variant = it.variant,
                                unit = it.unit,
                                price = it.unit_price,
                                quantity = it.quantity,
                                subTotal = it.line_subtotal,
                                hsnCode = it.hsn_code,
                                gstRate = it.gst_rate,
                                cgstAmount = it.cgst_amount,
                                sgstAmount = it.sgst_amount,
                                igstAmount = it.igst_amount,
                                taxableValue = it.taxable_amount,
                                isSynced = true
                            )
                        }
                    )
                }
                if (sb.bill_id > maxProcessedId) maxProcessedId = sb.bill_id
            }

            val newCursor = if (firstSkippedId != Int.MAX_VALUE)
                minOf(maxProcessedId, firstSkippedId - 1)
            else
                maxProcessedId
            if (newCursor > afterId) {
                cursorPrefs.edit().putInt(cursorKey, newCursor).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Propagate voids made on OTHER terminals (Sync re-audit, cancellation
     * propagation). The R3 bill mirror uses an append-only id cursor, so a
     * cancellation (a flag flip on an existing bill) wouldn't reach this device.
     * This pulls bills cancelled since a server-set `updated_at` cursor and marks
     * the local copy cancelled. Idempotent; safe to advance the cursor freely
     * (a not-yet-mirrored bill will arrive already-cancelled via pullBills).
     */
    suspend fun pullBillCancellations() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        val cursorPrefs = context.getSharedPreferences("sync_cursors", Context.MODE_PRIVATE)
        val shopId = currentShopIdOrNull() ?: -1
        val cursorKey = "bills_cancel_since_$shopId"
        val since = cursorPrefs.getLong(cursorKey, 0L)

        try {
            val cancelled = api.getBillCancellations(token, since)
            Log.d(SYNC_TAG, "pullBillCancellations: ${cancelled.size} void(s) since $since")
            val billDao = db.billDao()
            var maxUpdatedAt = since

            for (c in cancelled) {
                c.updated_at?.let { if (it > maxUpdatedAt) maxUpdatedAt = it }
                if (c.bill_number.isBlank()) continue
                val local = billDao.getByBillNumber(c.bill_number) ?: continue
                if (local.isCancelled) continue
                billDao.markBillCancelled(local.id, c.cancelled_at ?: appNow())
                // Void came FROM the server — mark acknowledged so we don't re-push it.
                billDao.markCancelSynced(local.id)
            }

            if (maxUpdatedAt > since) {
                cursorPrefs.edit().putLong(cursorKey, maxUpdatedAt).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun syncGstProfile() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return

        val profile = db.gstProfileDao().get()

        /**
         * Uploads [p] and stores whatever the server echoes back.
         *
         * `address` is included. It used to be left out of this request, so
         * every push through the sync engine sent address = null and the
         * server's `data.address or existing.address` quietly kept the old
         * value — or wrote "" on a fresh row. The settings screen sent it,
         * the background sync didn't, so whether your address survived
         * depended on which path saved it.
         */
        suspend fun push(p: GstProfile) {
            val request = GstProfileRequest(
                gstin = p.gstin,
                legal_name = p.legalName,
                trade_name = p.tradeName,
                gst_scheme = p.gstScheme,
                registration_type = p.registrationType,
                state_code = p.stateCode,
                address = p.address
            )
            val response = api.upsertGstProfile(token, request)
            db.gstProfileDao().insert(p.copy(
                legalName = response.legal_name,
                tradeName = response.trade_name,
                gstScheme = response.gst_scheme,
                registrationType = response.registration_type,
                stateCode = response.state_code,
                address = response.address ?: p.address,
                syncStatus = "synced"
            ))
        }

        try {
            // ── PUSH FIRST (mirror syncStoreInfo, Issue 15) ────────────────
            // A dirty local profile is an un-pushed user edit. Upload it and
            // mark synced; do NOT pull-and-overwrite first or the edit is lost.
            if (profile != null && profile.syncStatus != "synced") {
                push(profile)
                return
            }

            // ── PULL (local clean OR fresh device with no local profile) ───
            // R8: previously this method did nothing when clean, so a GST
            // profile edited on another terminal never reached this device
            // (one-way sync). Pull the server-canonical row now.
            val response = try {
                api.getGstProfile(token)
            } catch (e: retrofit2.HttpException) {
                if (e.code() != 404) throw e

                // 404 = the server holds no profile for this shop.
                //
                // Recoverable, and it has to be recovered here. A device
                // whose local row says "synced" never enters the push branch
                // above, so if the server row is gone — database reset,
                // restored from an older backup, shop re-created, or a push
                // that was marked synced without ever landing — the pull 404s
                // on every single sync pass and the profile is never restored.
                // That is the repeating "GET /gst/profile 404" in the log.
                //
                // We hold the only surviving copy, so upload it.
                if (profile != null && profile.gstin.isNotBlank()) {
                    Log.w(SYNC_TAG, "syncGstProfile: server has no profile — re-pushing local copy")
                    push(profile)
                } else {
                    // Nothing here either: GST genuinely isn't set up yet.
                    // Expected on a new shop, not an error.
                    Log.i(SYNC_TAG, "syncGstProfile: no profile on server or device yet")
                }
                return
            }

            // Blank-guard: never let an empty server row wipe a populated
            // local profile. Only adopt the pull if it carries a real GSTIN.
            if (response.gstin.isBlank()) return

            val merged = (profile ?: GstProfile(gstin = response.gstin)).copy(
                gstin = response.gstin,
                legalName = response.legal_name,
                tradeName = response.trade_name,
                gstScheme = response.gst_scheme,
                registrationType = response.registration_type,
                stateCode = response.state_code,
                address = response.address ?: (profile?.address ?: ""),
                syncStatus = "synced"
            )
            db.gstProfileDao().insert(merged)
        } catch (e: Exception) {
            // Offline/transient → leave any local edit pending for next pass.
            Log.w(SYNC_TAG, "syncGstProfile: ${e.message}")
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

            // Only mark synced the invoices the server confirmed in its
            // local_id → server_id map. Rows missing from the map stay pending
            // and retry next pass. We must NOT blanket-mark on an empty map:
            // that would drop rows the server rejected and leave serverId = null.
            // The endpoint is idempotent on (shop_id, local_id), so retrying a
            // row that did land server-side is harmless.
            response.invoice_id_map.forEach { (localIdStr, serverId) ->
                val localId = localIdStr.toIntOrNull() ?: return@forEach
                db.gstSalesInvoiceDao().markSynced(localId, serverId)
            }
            if (response.invoice_id_map.isEmpty()) {
                Log.w(SYNC_TAG, "syncGstInvoices: 2xx but empty id_map — leaving " +
                    "${pending.size} invoice(s) pending for retry")
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
                        cancelled_at   = inv.cancelledAt ?: appNow()
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
                        cancelled_at     = bill.cancelledAt ?: appNow()
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

    /**
     * Parse an EVENT-time value pulled from the backend into epoch-millis.
     *
     * Backend event datetimes (created_at, note_date, …) are NAIVE wall clock in
     * the shop timezone (Bucket A, e.g. "2026-06-24T00:10:00" or "… 00:10:00").
     * They MUST be parsed in [AppTime.ZONE] — NOT UTC — otherwise the instant is
     * shifted by the shop's offset and then shifted AGAIN when we format it back,
     * which is the round-trip bug (#85) this replaces. Some fields arrive as a
     * numeric epoch-millis string (a true instant); those are returned as-is.
     */
    private fun parseIsoDate(isoString: String?): Long? =
        com.example.easy_billing.util.WallClockParser
            .parse(isoString, com.example.easy_billing.util.AppTime.ZONE)

    /**
     * Parse a CURSOR/technical value (Bucket B, e.g. updated_at) into epoch-millis.
     * These are stored UTC on the backend, so parse in UTC.
     */
    private fun parseUtcDate(isoString: String?): Long? =
        com.example.easy_billing.util.WallClockParser
            .parse(isoString, java.util.TimeZone.getTimeZone("UTC"))

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

            // Dedupe on a composite natural key — NOT the originating device's
            // local_id, which collided with this device's own autoincrement ids and
            // could skip a mirrored batch or pin a colliding id (H2). Batches feed
            // COGS, so a skip/dup distorts cost; the key identifies a purchase line
            // (product + invoice + qty + unit cost + second). Insert with id=0 so
            // Room assigns a fresh local primary key.
            fun batchKey(productId: Int, invoice: String?, qty: Double, cost: Double, createdMs: Long) =
                "$productId|${invoice ?: ""}|${"%.3f".format(qty)}|${"%.3f".format(cost)}|${createdMs / 1000}"
            val productDao = db.productDao()
            val existingKeys = db.purchaseBatchDao().getAllBatchesGlobal()
                .mapTo(HashSet()) {
                    batchKey(it.productId, it.invoiceNumber, it.quantityPurchased, it.unitCostExcludingTax, it.createdAt)
                }

            db.withTransaction {
                for (b in batches) {
                    // Map server product_id → local Room product ID (server sends 45,
                    // local queries use Room id 3; storing the server id hid batches).
                    val localProduct = productDao.getByServerId(b.product_id)
                    val localProductId = localProduct?.id
                    if (localProductId == null) {
                        Log.w(SYNC_TAG, "pullPurchaseBatches: skipping batch ${b.local_id} — no local product for serverId=${b.product_id}")
                        continue
                    }
                    val createdMs = parseIsoDate(b.created_at) ?: appNow()
                    val key = batchKey(localProductId, b.invoice_number, b.quantity_purchased, b.unit_cost_excluding_tax, createdMs)
                    if (key !in existingKeys) {
                        existingKeys.add(key)
                        db.purchaseBatchDao().insertBatch(
                            com.example.easy_billing.db.PurchaseBatch(
                                id = 0,
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

                                createdAt = createdMs,
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

        // Delta cursor (M3): append-only server id, keyed by shop, so we no longer
        // refetch the entire credit-note history on every (throttled) cascade.
        val cursorPrefs = context.getSharedPreferences("sync_cursors", Context.MODE_PRIVATE)
        val cnShopId = currentShopIdOrNull() ?: -1
        val cursorKey = "credit_notes_after_id_$cnShopId"
        val afterId = cursorPrefs.getInt(cursorKey, 0)

        try {
            val notes = api.getCreditNotes(token, afterId)
            var maxId = afterId

            // Dedupe on note_number — a stable business key present on both
            // locally-created and pulled notes. Matching on the originating device's
            // local_id (and reusing it as our primary key) collided with this
            // device's own autoincrement ids, so a mirrored note was skipped or
            // overwrote an unrelated row (H2). Insert with id=0 so Room assigns a
            // fresh local primary key.
            val existingNoteNumbers = db.creditNoteDao().getAll()
                .mapNotNull { it.noteNumber.takeIf { n -> n.isNotBlank() } }
                .toHashSet()
            db.withTransaction {
                for (noteDto in notes) {
                    if (noteDto.id > maxId) maxId = noteDto.id
                    val isDuplicate = noteDto.note_number.isNotBlank() &&
                        noteDto.note_number in existingNoteNumbers
                    if (!isDuplicate) {
                        if (noteDto.note_number.isNotBlank()) existingNoteNumbers.add(noteDto.note_number)
                        val newId = db.creditNoteDao().insert(
                            com.example.easy_billing.db.CreditNote(
                                id = 0,
                                noteNumber = noteDto.note_number,
                                noteDate = parseIsoDate(noteDto.note_date) ?: appNow(),
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
                                createdAt = parseIsoDate(noteDto.created_at) ?: appNow(),
                                updatedAt = parseUtcDate(noteDto.updated_at) ?: appNow()  // Bucket B (UTC)
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
            // Notes are inserted-once (dedupe by note_number), so advancing to the
            // newest id we saw can't skip an un-inserted row.
            if (maxId > afterId) {
                cursorPrefs.edit().putInt(cursorKey, maxId).apply()
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

            // Map server product id → local product id ONCE (H3). Returns are
            // stored with the LOCAL product id (matching how they're created), so
            // the column has one consistent meaning and the device's own pushed
            // returns dedupe correctly when they come back on pull.
            val productByServerId = db.productDao().getAll().associateBy { it.serverId }

            db.withTransaction {
                // Dedup index built ONCE up front (Issue 13). Primary key is the
                // note_number — a stable business key present on both locally-created
                // (PurchaseReturnViewModel sets it) and pulled rows (H3). Fallback
                // for legacy rows without a note is (createdAt rounded to seconds,
                // localProductId); seconds because the server round-trips created_at
                // through second-precision ISO, so millisecond compares would miss.
                val existingRows = db.purchaseReturnDao().getRecent(10000)
                val existingNotes = existingRows
                    .mapNotNull { it.noteNumber?.takeIf { n -> n.isNotBlank() } }
                    .toHashSet()
                val existingKeys = existingRows
                    .mapTo(HashSet()) { (it.createdAt / 1000) to it.productId }
                for (r in returns) {
                    val localProductId = r.shop_product_id?.let { productByServerId[it]?.id }
                    val note = r.note_number?.takeIf { it.isNotBlank() }
                    val fallbackKey = ((parseIsoDate(r.created_at) ?: 0L) / 1000) to localProductId

                    val isDuplicate =
                        if (note != null) note in existingNotes
                        else fallbackKey in existingKeys
                    if (!isDuplicate) {
                        if (note != null) existingNotes.add(note) else existingKeys.add(fallbackKey)
                        db.purchaseReturnDao().insert(
                            com.example.easy_billing.db.PurchaseReturn(
                                id = 0, // Auto-generate local ID
                                productId = localProductId,
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
                                createdAt = parseIsoDate(r.created_at) ?: appNow(),
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
