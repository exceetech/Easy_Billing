package com.example.easy_billing.sync

import android.content.Context
import androidx.room.withTransaction
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.BillingSettings
import com.example.easy_billing.db.CreditAccount
import com.example.easy_billing.db.Inventory
import com.example.easy_billing.db.StoreInfo
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.CreateBillRequest
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
import com.example.easy_billing.network.PurchaseDto
import com.example.easy_billing.network.PurchaseItemDto
import com.example.easy_billing.network.PurchaseReturnDto
import com.example.easy_billing.network.PurchaseReturnSyncRequest
import com.example.easy_billing.network.PurchaseSyncRequest
import com.example.easy_billing.network.ScrapDto
import com.example.easy_billing.network.ScrapSyncRequest
import android.util.Log

class SyncManager(private val context: Context) {


    suspend fun syncAll() {

        // 🔥 ORDER MATTERS — products before anything that references
        // them (purchases, bills, returns, scrap), pulls last.

        syncAccounts()           // account first
        syncCredit()             // then transactions
        syncShopProducts()       // 🆕 push unsynced shop_product (and global)
        syncInventory()          // 🔥 inventory BEFORE bills
        syncBills()              // then bills
        syncPurchases()          // 🆕 push purchase invoices + items
        syncPurchaseReturns()    // 🆕 push returns
        syncScrapEntries()       // 🆕 push scrap
        syncGstProfile()
        syncGstSales()
        syncGstPurchases()
        syncGstInvoices()        // 🆕 push GST-aware invoice batch
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
                    "Bearer $token",
                    AddProductRequest(
                        name             = product.name,
                        variant_name     = product.variant,
                        unit             = product.unit ?: "piece",
                        price            = product.price,
                        track_inventory  = product.trackInventory,
                        initial_stock    = null,
                        cost_price       = null,
                        hsn_code         = product.hsnCode,
                        default_gst_rate = product.defaultGstRate
                    )
                )
                if (response.product_id > 0) {
                    db.productDao().setServerId(product.id, response.product_id)
                    Log.d(SYNC_TAG, "  product '${product.name}' → server id ${response.product_id}")
                }

                // Always also register globally — best-effort.
                runCatching {
                    api.registerGlobalProduct(
                        "Bearer $token",
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
                    sales_igst_percentage    = item.salesIgstPercentage
                )
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
                // Forward the supplier-printed invoice date so the
                // backend's `purchases.invoice_date` column actually
                // gets populated (was always null before this).
                invoice_date     = p.invoiceDate,
                is_credit        = p.isCredit,
                credit_account_id = p.creditAccountId,
                created_at       = p.createdAt,
                items            = items
            )
        }

        return try {
            Log.d(SYNC_TAG, "pushPurchases: POST /purchases/sync with ${dtoBatch.size} purchase(s)")
            val response = api.syncPurchases(
                "Bearer $token", PurchaseSyncRequest(dtoBatch)
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
            PurchaseReturnDto(
                local_id          = r.id,
                shop_id           = r.shopId,
                shop_product_id   = serverProductId,
                product_name      = r.productName,
                variant_name      = r.variantName,
                hsn_code          = r.hsnCode,
                quantity_returned = r.quantityReturned,
                taxable_amount    = r.taxableAmount,
                invoice_value     = r.invoiceValue,
                cgst_percentage   = r.cgstPercentage,
                sgst_percentage   = r.sgstPercentage,
                igst_percentage   = r.igstPercentage,
                cgst_amount       = r.cgstAmount,
                sgst_amount       = r.sgstAmount,
                igst_amount       = r.igstAmount,
                state             = r.state,
                supplier_gstin    = r.supplierGstin,
                supplier_name     = r.supplierName,
                is_credit         = r.isCredit,
                credit_account_id = r.creditAccountId,
                created_at        = r.createdAt
            )
        }

        try {
            api.syncPurchaseReturns("Bearer $token", PurchaseReturnSyncRequest(dtos))
            pending.forEach { db.purchaseReturnDao().markSynced(it.id) }
        } catch (e: Exception) {
            e.printStackTrace()
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
            api.syncScrap("Bearer $token", ScrapSyncRequest(dtos))
            pending.forEach { db.scrapDao().markSynced(it.id) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun syncBills() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        val bills = db.billDao().getUnsyncedBills()
        val productDao = db.productDao()

        for (bill in bills) {

            try {

                val items = db.billItemDao().getItemsForBill(bill.id)

                val apiItems = items.mapNotNull {

                    val product = productDao.getById(it.productId)
                    val serverId = product?.serverId

                    if (serverId == null || serverId <= 0) {
                        println("❌ Skipping unsynced product: ${product?.name}")
                        return@mapNotNull null
                    }

                    BillItemRequest(
                        shop_product_id = serverId,
                        quantity = it.quantity,
                        variant = product.variant
                    )
                }

                if (apiItems.size != items.size) {
                    println("❌ Skipping bill ${bill.id} → invalid products")
                    continue
                }

                val request = CreateBillRequest(
                    bill_number = "",
                    items = apiItems,
                    payment_method = bill.paymentMethod,
                    discount = bill.discount,
                    gst = bill.gst,
                    total_amount = bill.total
                )

                val response = api.createBill("Bearer $token", request)

                if (response.bill_number.isNotEmpty()) {
                    db.billDao().updateBillNumber(bill.id, response.bill_number)
                }

                db.billDao().markBillSynced(bill.id)
                db.billItemDao().markItemsSynced(bill.id)

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
            val response = api.getStoreSettings("Bearer $token")

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

            val response = api.getBillingSettings("Bearer $token")

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
                    "Bearer $token",
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
                    "Bearer $token",
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

            val accounts = api.getCreditAccounts("Bearer $token")

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
                "Bearer $token",
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

            val serverInventory = api.getInventory("Bearer $token")

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

                    val existing = inventoryDao.getInventory(product.id)

                    if (existing != null) {
                        // 🔥 PROTECT LOCAL CHANGES
                        if (!existing.isSynced) {
                            println("⏳ Skipping pull for productId=${product.id} → local has unsynced changes")
                            continue
                        }

                        inventoryDao.update(
                            existing.copy(
                                currentStock = item.stock,
                                averageCost = item.avg_cost,
                                isActive = item.is_active,
                                isSynced = true
                            )
                        )
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

                        println("✅ Inserted inventory for productId=${product.id}")
                    }
                }
            }

            // 🔥 FIX MISSING PRODUCTS (CRITICAL FOR REINSTALL CASE)
            if (missingProductIds.isNotEmpty()) {

                println("🔄 Missing products detected → refetching products")

                try {

                    val backendProducts =
                        RetrofitClient.api.getMyProducts("Bearer $token")

                    backendProducts.forEach {

                        if (missingProductIds.contains(it.id)) {

                            productDao.insert(
                                com.example.easy_billing.db.Product(
                                    id = it.id,
                                    serverId = it.id,
                                    name = it.name,
                                    variant = it.variant ?: "",
                                    unit = it.unit,
                                    price = it.price,
                                    trackInventory = true,
                                    isCustom = false
                                )
                            )

                            println("✅ Inserted missing product ${it.id}")
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
            val response = api.upsertGstProfile("Bearer $token", request)
            
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
                record_id = record.id,
                invoice_number = record.invoiceNumber,
                invoice_date = record.invoiceDate,
                customer_type = record.customerType,
                customer_gstin = record.customerGstin,
                place_of_supply = record.placeOfSupply,
                supply_type = record.supplyType,
                total_invoice_value = record.totalAmount,
                taxable_value = record.taxableValue,
                cgst_amount = record.cgstAmount,
                sgst_amount = record.sgstAmount,
                igst_amount = record.igstAmount,
                cess_amount = 0.0,
                hsn_code = record.hsnCode,
                gst_rate = record.gstRate,
                device_id = record.deviceId
            )
        }

        try {
            val response = api.syncGstSales("Bearer $token", GstSalesSyncRequest(recordsDto))
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
            val response = api.syncGstPurchases("Bearer $token", GstPurchaseSyncRequest(recordsDto))
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
                    net_value             = item.netValue
                )
            }
            CreateGstSalesInvoiceDto(
                local_id       = inv.id,
                bill_id        = inv.billId,
                invoice_type   = inv.invoiceType,
                gst_scheme     = inv.gstScheme,
                customer_name  = inv.customerName,
                business_name  = inv.businessName,
                customer_phone = inv.customerPhone,
                customer_gst   = inv.customerGst,
                customer_state = inv.customerState,
                subtotal       = inv.subtotal,
                total_cgst     = inv.totalCgst,
                total_sgst     = inv.totalSgst,
                total_igst     = inv.totalIgst,
                total_tax      = inv.totalTax,
                grand_total    = inv.grandTotal,
                created_at     = inv.createdAt,
                items          = items
            )
        }

        try {
            Log.d(SYNC_TAG, "syncGstInvoices: POST /gst-sales/sync with ${dtoBatch.size} invoice(s)")
            val response = api.syncGstSalesInvoices(
                "Bearer $token",
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
}