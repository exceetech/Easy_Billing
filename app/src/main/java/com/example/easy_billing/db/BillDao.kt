package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BillDao {

    @Insert
    suspend fun insertBill(bill: Bill): Long

    @Insert
    suspend fun insertItems(items: List<BillItem>)

    @Query("SELECT * FROM bills ORDER BY id DESC")
    suspend fun getAllBills(): List<Bill>

    @Query("SELECT * FROM bill_items WHERE billId = :billId")
    suspend fun getItemsForBill(billId: Int): List<BillItem>

    @Query("SELECT * FROM bills WHERE id = :billId")
    suspend fun getBillById(billId: Int): Bill

    @Query("DELETE FROM bills")
    suspend fun deleteAllBills()

    @Query("DELETE FROM bill_items")
    suspend fun deleteAllItems()

    @Query("SELECT * FROM bills WHERE is_synced = 0")
    suspend fun getUnsyncedBills(): List<Bill>

    @Query("UPDATE bills SET billNumber = :billNumber WHERE id = :billId")
    fun updateBillNumber(billId: Int, billNumber: String)

    @Query("UPDATE bills SET is_synced = 1 WHERE id = :id")
    suspend fun markBillSynced(id: Int)
}