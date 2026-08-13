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

    /**
     * Unsynced rows to push, oldest first so the uploaded order matches
     * the real timeline. Deliberately excludes "action" rows — those are
     * local-only, full click-level detail that's too high-volume to sync
     * automatically; they only leave the device via the one-shot
     * diagnostic-report upload (see [getAllOrderedByTime]).
     */
    @Query(
        """
        SELECT * FROM user_event_logs
        WHERE is_synced = 0 AND event_type != 'action'
        ORDER BY created_at ASC LIMIT :limit
        """
    )
    suspend fun getUnsynced(limit: Int = 200): List<UserEventLog>

    @Query("UPDATE user_event_logs SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Int>)

    /**
     * The FULL local table — every action, error, and validation row,
     * regardless of sync state — for the one-shot diagnostic report
     * upload (see util/DiagnosticReportUploader.kt). Oldest first so it
     * reads like a timeline.
     */
    @Query("SELECT * FROM user_event_logs ORDER BY created_at ASC")
    suspend fun getAllOrderedByTime(): List<UserEventLog>

    /**
     * Caps the local table so a device that's offline for a long stretch
     * — or just generating a lot of click-level "action" rows day to day
     * — doesn't let this grow unbounded on the device. Keeps only the
     * most recent [keep] rows; older ones are dropped regardless of sync
     * state, since "action" rows never sync at all and would otherwise
     * never be cleared out. The cap is intentionally generous (tens of
     * thousands) since a single busy billing day can generate hundreds of
     * click-level events and this is now the only retention control for
     * those.
     */
    @Query(
        """
        DELETE FROM user_event_logs
        WHERE id NOT IN (
            SELECT id FROM user_event_logs ORDER BY created_at DESC LIMIT :keep
        )
        """
    )
    suspend fun trimToMostRecent(keep: Int = 20000)

    /**
     * Only ever removes already-synced error/validation rows — "action"
     * rows are never marked synced, so this never touches them. Local
     * storage pressure from those is handled entirely by
     * [trimToMostRecent].
     */
    @Query("DELETE FROM user_event_logs WHERE is_synced = 1")
    suspend fun deleteSynced()
}
