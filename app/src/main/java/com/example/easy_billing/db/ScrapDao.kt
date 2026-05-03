package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScrapDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ScrapEntry): Long

    @Query("SELECT * FROM scrap_table ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<ScrapEntry>

    @Query("SELECT * FROM scrap_table WHERE productId = :productId")
    suspend fun getByProduct(productId: Int): List<ScrapEntry>

    @Query("SELECT * FROM scrap_table WHERE is_synced = 0")
    suspend fun getUnsynced(): List<ScrapEntry>

    @Query("UPDATE scrap_table SET is_synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Int)
}
