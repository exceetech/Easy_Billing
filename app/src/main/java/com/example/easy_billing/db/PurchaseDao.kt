package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PurchaseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(purchase: Purchase): Long

    @Query("SELECT * FROM purchase_table ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<Purchase>

    @Query("SELECT * FROM purchase_table ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<Purchase>>

    @Query("SELECT * FROM purchase_table WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Purchase?

    @Query("SELECT * FROM purchase_table WHERE server_id = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: Int): Purchase?

    @Query("SELECT * FROM purchase_table WHERE is_synced = 0")
    suspend fun getUnsynced(): List<Purchase>

    @Query("SELECT COUNT(*) FROM purchase_table WHERE is_synced = 0")
    suspend fun countUnsynced(): Int

    @Query("UPDATE purchase_table SET is_synced = 1, server_id = :serverId WHERE id = :id")
    suspend fun markSynced(id: Int, serverId: Int?)

    /**
     * Voids a purchase. Status only — the ITC/inventory unwind is done by the
     * cancellation's bulk return, not by this flag. cancel_synced starts false
     * so the void is pushed to the server once (mirror of the bills table).
     */
    @Query("""
        UPDATE purchase_table
        SET is_cancelled = 1, cancelled_at = :at, cancel_synced = 0, is_synced = 0
        WHERE id = :id
    """)
    suspend fun markPurchaseCancelled(id: Int, at: Long)

    /** Cancelled purchases whose void hasn't been pushed to the server yet. */
    @Query("SELECT * FROM purchase_table WHERE is_cancelled = 1 AND cancel_synced = 0")
    suspend fun getCancelledUnsynced(): List<Purchase>

    /** Acknowledged by the server — stop re-pushing this void. */
    @Query("UPDATE purchase_table SET cancel_synced = 1 WHERE id = :id")
    suspend fun markPurchaseCancelSynced(id: Int)
}
