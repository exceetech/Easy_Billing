package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao // 🔥 REQUIRED
interface BillItemDao {

    @Query("SELECT * FROM bill_items WHERE billId = :billId")
    suspend fun getItemsForBill(billId: Int): List<BillItem>

    @Query("UPDATE bill_items SET is_synced = 1 WHERE billId = :billId")
    suspend fun markItemsSynced(billId: Int)

    @Insert
    suspend fun insertAll(items: List<BillItem>)

    @Query("DELETE FROM bill_items")
    suspend fun deleteAll()
}