package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PurchaseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(purchase: Purchase): Long

    @Query("SELECT * FROM purchase_table ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<Purchase>

    @Query("SELECT * FROM purchase_table ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<Purchase>>

    @Query("SELECT * FROM purchase_table WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Purchase?

    @Query("SELECT * FROM purchase_table WHERE is_synced = 0")
    suspend fun getUnsynced(): List<Purchase>

    @Query("UPDATE purchase_table SET is_synced = 1, server_id = :serverId WHERE id = :id")
    suspend fun markSynced(id: Int, serverId: Int?)
}
