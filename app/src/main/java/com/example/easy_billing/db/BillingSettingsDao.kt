package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BillingSettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: BillingSettings)

    @Query("SELECT * FROM billing_settings LIMIT 1")
    suspend fun get(): BillingSettings?

    @Query("DELETE FROM billing_settings")
    suspend fun clear()
}