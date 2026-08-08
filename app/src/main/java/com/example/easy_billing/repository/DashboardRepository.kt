package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Inventory
import com.example.easy_billing.db.Product
import com.example.easy_billing.db.ProductSalesAgg
import com.example.easy_billing.db.StoreInfo
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.SaveTokenRequest

/**
 * Data-access layer for DashboardActivity. Thin 1:1 passthroughs — no
 * behavior change from the direct db/RetrofitClient calls they replace.
 *
 * loadProducts()'s methods are raw data-fetch passthroughs only — the
 * merge/decision logic (matching backend rows to local ones) and the
 * generation-counter staleness check (loadProductsGeneration AtomicInteger)
 * stay in the Activity, unchanged, since they're tightly coupled to that
 * counter and to loadProducts() being called from three different places
 * with different sync semantics (onResume() vs invoiceLauncher's callback).
 */
class DashboardRepository(context: Context) {

    private val db = AppDatabase.getDatabase(context)

    suspend fun getOverview(token: String) =
        RetrofitClient.api.getOverview(token, "today", null, null)

    suspend fun getProfile(token: String) =
        RetrofitClient.api.getProfile(token)

    suspend fun saveFcmToken(authToken: String, fcmToken: String) =
        RetrofitClient.api.saveFcmToken(authToken, SaveTokenRequest(fcmToken))

    suspend fun getAiReport() =
        RetrofitClient.api.getAiReport()

    suspend fun getSubscription(token: String) =
        RetrofitClient.api.getSubscription(token)

    suspend fun getStoreInfo(): StoreInfo? =
        db.storeInfoDao().get()

    // ===== loadProducts() raw data-fetch calls =====
    // These are 1:1 passthroughs only — the merge/decision logic in
    // loadProducts() (matching backend rows to local ones, the generation-
    // counter staleness check) stays in the Activity unchanged. Do not move
    // that logic here; it's tightly coupled to loadProductsGeneration.

    suspend fun getMyProducts(token: String) =
        RetrofitClient.api.getMyProducts(token)

    suspend fun getAllProductsWithInactive(): List<Product> =
        db.productDao().getAllWithInactive()

    suspend fun updateProduct(product: Product): Int =
        db.productDao().update(product)

    suspend fun upsertProduct(product: Product): Long =
        db.productDao().upsert(product)

    suspend fun getAllInventory(): List<Inventory> =
        db.inventoryDao().getAll()

    suspend fun getSalesAggByProduct(): List<ProductSalesAgg> =
        db.billItemDao().getSalesAggByProduct()

    // ===== addToCart() / showDeleteDialog() raw data-fetch calls =====

    suspend fun getInventory(productId: Int): Inventory? =
        db.inventoryDao().getInventory(productId)

    suspend fun getTotalStock(productId: Int): Double =
        com.example.easy_billing.InventoryManager.getTotalStock(db, productId)

    suspend fun deactivateProductLocal(productId: Int) =
        db.productDao().deactivate(productId)

    suspend fun updateInventory(inventory: Inventory) =
        db.inventoryDao().update(inventory)

    suspend fun deactivateProductRemote(token: String, serverId: Int) =
        RetrofitClient.api.deactivateProduct(token, serverId)

    suspend fun markDeactivateSynced(productId: Int) =
        db.productDao().markDeactivateSynced(productId)
}
