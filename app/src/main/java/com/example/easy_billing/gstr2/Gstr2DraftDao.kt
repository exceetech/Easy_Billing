package com.example.easy_billing.gstr2

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface Gstr2DraftDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(draft: Gstr2DraftEntity)

    @Delete
    suspend fun delete(draft: Gstr2DraftEntity)

    @Query("SELECT * FROM gstr2_drafts ORDER BY updated_at DESC")
    fun getAllDrafts(): Flow<List<Gstr2DraftEntity>>

    @Query("""
        SELECT * FROM gstr2_drafts 
        WHERE gstin = :gstin 
          AND financial_year = :fy 
          AND period = :period 
          AND return_type = :returnType
        LIMIT 1
    """)
    suspend fun getDraft(gstin: String, fy: String, period: String, returnType: String): Gstr2DraftEntity?
}
