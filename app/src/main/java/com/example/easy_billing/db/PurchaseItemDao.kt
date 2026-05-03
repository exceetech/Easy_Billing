package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PurchaseItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PurchaseItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PurchaseItem>): List<Long>

    @Query("SELECT * FROM purchase_items_table WHERE purchaseId = :purchaseId")
    suspend fun getByPurchase(purchaseId: Int): List<PurchaseItem>

    @Query("SELECT * FROM purchase_items_table WHERE productId = :productId ORDER BY id DESC")
    suspend fun getByProduct(productId: Int): List<PurchaseItem>

    @Query("SELECT * FROM purchase_items_table WHERE is_synced = 0")
    suspend fun getUnsynced(): List<PurchaseItem>

    @Query("UPDATE purchase_items_table SET is_synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Int)

    @Query("UPDATE purchase_items_table SET is_synced = 1 WHERE purchaseId = :purchaseId")
    suspend fun markAllSyncedForPurchase(purchaseId: Int)
}
