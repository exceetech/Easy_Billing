package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `gst_profile` table.
 *
 * Exposes both suspend (one-shot) reads for use during sync flows
 * and a Flow-based observer for offline-first UI binding.
 */
@Dao
interface GstProfileDao {

    /* ------------------------------------------------------------------
     *  Upsert
     * ------------------------------------------------------------------ */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: GstProfile)

    /* ------------------------------------------------------------------
     *  One-shot reads
     * ------------------------------------------------------------------ */

    @Query("SELECT * FROM gst_profile WHERE id = 1 LIMIT 1")
    suspend fun get(): GstProfile?

    @Query("SELECT * FROM gst_profile WHERE shop_id = :shopId LIMIT 1")
    suspend fun getByShopId(shopId: String): GstProfile?

    @Query("SELECT * FROM gst_profile WHERE sync_status = 'pending' OR sync_status = 'failed'")
    suspend fun getUnsynced(): List<GstProfile>

    /* ------------------------------------------------------------------
     *  Reactive (offline-first UI)
     * ------------------------------------------------------------------ */

    @Query("SELECT * FROM gst_profile WHERE id = 1 LIMIT 1")
    fun observe(): Flow<GstProfile?>

    @Query("SELECT * FROM gst_profile WHERE shop_id = :shopId LIMIT 1")
    fun observeByShopId(shopId: String): Flow<GstProfile?>

    /* ------------------------------------------------------------------
     *  Targeted writes
     * ------------------------------------------------------------------ */

    @Query("UPDATE gst_profile SET sync_status = :status, updated_at = :updatedAt WHERE id = 1")
    suspend fun updateSyncStatus(status: String, updatedAt: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE gst_profile
           SET legal_name        = :legalName,
               trade_name        = :tradeName,
               gst_scheme        = :scheme,
               registration_type = :regType,
               state_code        = :stateCode,
               address           = :address,
               sync_status       = 'synced',
               updated_at        = :updatedAt
         WHERE id = 1
        """
    )
    suspend fun enrichFromServer(
        legalName: String,
        tradeName: String,
        scheme: String,
        regType: String,
        stateCode: String,
        address: String,
        updatedAt: Long
    )

    @Query("DELETE FROM gst_profile")
    suspend fun clear()
}
