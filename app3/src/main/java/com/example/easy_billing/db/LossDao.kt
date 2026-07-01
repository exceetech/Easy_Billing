package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LossDao {

    @Insert
    suspend fun insert(loss: LossEntry)

    // 🔥 total loss for one product
    @Query("SELECT IFNULL(SUM(amount), 0) FROM loss_table WHERE productId = :productId")
    suspend fun getLossForProduct(productId: Int): Double

    // 🔥 total loss overall
    @Query("SELECT IFNULL(SUM(amount), 0) FROM loss_table")
    suspend fun getTotalLoss(): Double
}