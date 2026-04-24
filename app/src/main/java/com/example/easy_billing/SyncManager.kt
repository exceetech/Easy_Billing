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

class SyncManager(private val context: Context) {


    suspend fun syncAll() {

        // 🔥 ORDER MATTERS

        syncAccounts()        // account first
        syncCredit()          // then transactions
        syncInventory()       // 🔥 inventory BEFORE bills
        syncBills()           // then bills
        syncStoreInfo()       // pull latest
        syncBillingSettings() // pull settings
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
                        type = txn.type
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

        val validRequests = mutableListOf<com.example.easy_billing.network.InventoryLogRequest>()
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
                    var product = productMap[item.product_id]
                        ?: productDao.getByServerId(item.product_id)

                    if (product == null) {
                        println("⚠️ Product missing locally: ${item.product_id}")
                        missingProductIds.add(item.product_id)
                        continue
                    }

                    val existing = inventoryDao.getInventory(product.id)

                    if (existing != null) {

                        // 🔥 SAFE MERGE (VERY IMPORTANT)
                        val finalStock = if (existing.isSynced) {
                            item.stock
                        } else {
                            maxOf(existing.currentStock, item.stock)
                        }

                        inventoryDao.update(
                            existing.copy(
                                currentStock = finalStock,
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
}