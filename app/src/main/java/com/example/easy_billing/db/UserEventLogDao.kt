package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for the local `user_event_logs` breadcrumb table. See
 * [UserEventLog] for what this is for.
 */
@Dao
interface UserEventLogDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: UserEventLog): Long

    /** Unsynced rows to push, oldest first so the uploaded order matches the real timeline. */
    @Query("SELECT * FROM user_event_logs WHERE is_synced = 0 ORDER BY created_at ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 200): List<UserEventLog>

    @Query("UPDATE user_event_logs SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Int>)

    /**
     * Caps the local table so a device that's offline for a long stretch
     * doesn't let this grow unbounded — keeps only the most recent
     * [keep] rows. Already-synced rows are the ones dropped first since
     * the backend is the durable copy once synced; if the cap still has
     * to eat into unsynced rows, the oldest breadcrumbs go first (least
     * useful for a "what just happened" investigation anyway).
     */
    @Query(
        """
        DELETE FROM user_event_logs
        WHERE id NOT IN (
            SELECT id FROM user_event_logs ORDER BY created_at DESC LIMIT :keep
        )
        """
    )
    suspend fun trimToMostRecent(keep: Int = 500)

    @Query("DELETE FROM user_event_logs WHERE is_synced = 1")
    suspend fun deleteSynced()
}
