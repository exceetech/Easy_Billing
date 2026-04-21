package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface InventoryTransactionDao {

    @Insert
    suspend fun insert(transaction: InventoryTransaction)

    @Query("SELECT * FROM inventory_transactions WHERE productId = :productId")
    suspend fun getTransactions(productId: Int): List<InventoryTransaction>
}