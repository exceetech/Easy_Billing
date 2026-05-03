package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PurchaseReturnDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PurchaseReturn): Long

    @Query("SELECT * FROM purchase_return_table ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<PurchaseReturn>

    @Query("SELECT * FROM purchase_return_table WHERE productId = :productId")
    suspend fun getByProduct(productId: Int): List<PurchaseReturn>

    @Query("SELECT * FROM purchase_return_table WHERE is_synced = 0")
    suspend fun getUnsynced(): List<PurchaseReturn>

    @Query("UPDATE purchase_return_table SET is_synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Int)
}
