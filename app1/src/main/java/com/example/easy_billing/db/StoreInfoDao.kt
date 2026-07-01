package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreInfoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(info: StoreInfo)

    @Query("SELECT * FROM store_info LIMIT 1")
    suspend fun get(): StoreInfo?

    @Query("SELECT * FROM store_info LIMIT 1")
    fun observe(): Flow<StoreInfo?>

    @Query("SELECT * FROM store_info WHERE is_synced = 0 LIMIT 1")
    suspend fun getUnsynced(): StoreInfo?

    @Query("UPDATE store_info SET is_synced = 1")
    suspend fun markSynced()

    @Query("DELETE FROM store_info")
    suspend fun clear()
}