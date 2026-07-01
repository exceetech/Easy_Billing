package com.example.easy_billing.gstr1

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface Gstr1DraftDao {

    /** Upsert — replaces any existing draft for the same id if called with an existing id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: Gstr1DraftEntity): Long

    @Query("SELECT * FROM gstr1_drafts ORDER BY updated_at DESC")
    suspend fun getAll(): List<Gstr1DraftEntity>

    @Query("SELECT * FROM gstr1_drafts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Gstr1DraftEntity?

    @Query("""
        SELECT * FROM gstr1_drafts
        WHERE gstin = :gstin AND financial_year = :fy AND period = :period
        LIMIT 1
    """)
    suspend fun find(gstin: String, fy: String, period: String): Gstr1DraftEntity?

    @Query("DELETE FROM gstr1_drafts WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM gstr1_drafts")
    suspend fun deleteAll()
}
