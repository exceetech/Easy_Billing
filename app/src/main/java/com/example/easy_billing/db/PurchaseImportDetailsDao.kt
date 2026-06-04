package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PurchaseImportDetailsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(details: PurchaseImportDetails): Long

    @Update
    suspend fun update(details: PurchaseImportDetails)

    @Query("SELECT * FROM purchase_import_details WHERE purchase_id = :purchaseId LIMIT 1")
    suspend fun getByPurchaseId(purchaseId: Int): PurchaseImportDetails?

    @Query("SELECT * FROM purchase_import_details WHERE sync_status = 'pending' OR sync_status = 'failed'")
    suspend fun getUnsynced(): List<PurchaseImportDetails>

    @Query("UPDATE purchase_import_details SET sync_status = 'synced', updated_at = :timestamp WHERE id = :id")
    suspend fun markSynced(id: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE purchase_import_details SET sync_status = 'failed', updated_at = :timestamp WHERE id = :id")
    suspend fun markFailed(id: Int, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM purchase_import_details WHERE purchase_id = :purchaseId")
    suspend fun deleteByPurchaseId(purchaseId: Int)
}
