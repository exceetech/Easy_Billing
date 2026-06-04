package com.example.easy_billing.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ImportServiceDao {

    @Insert
    suspend fun insert(importService: ImportService): Long

    @Insert
    suspend fun insertAll(importServices: List<ImportService>)

    @Update
    suspend fun update(importService: ImportService)

    @Query("SELECT * FROM import_services ORDER BY invoice_date DESC")
    fun getAllImportServicesLive(): LiveData<List<ImportService>>

    @Query("SELECT * FROM import_services ORDER BY invoice_date DESC")
    suspend fun getAllImportServices(): List<ImportService>

    @Query("SELECT * FROM import_services WHERE sync_status = 'pending'")
    suspend fun getPendingSyncImportServices(): List<ImportService>

    @Query("UPDATE import_services SET sync_status = 'synced' WHERE id = :id")
    suspend fun markAsSynced(id: Int)

    @Query("DELETE FROM import_services")
    suspend fun deleteAll()
}
