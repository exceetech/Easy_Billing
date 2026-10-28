package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.easy_billing.util.ModeDirTotal
import com.example.easy_billing.util.appNow

/**
 * The Money Book: one row for every real movement of money (v71).
 *
 * - Written in the SAME transaction as the business event that moved the money.
 * - Append-only: a refund or a mistake is a new opposite row. The only field that ever
 *   changes is [status] (PENDING -> RECEIVED when a UPI payment link is paid).
 * - [uid] is a random id that never repeats across phones / reinstalls; it is the sync key.
 * - [sourceType]/[sourceId] is the document that wrote the row (e.g. a credit note).
 *   [parentType]/[parentId] is the bill / purchase the money belongs to, so the refund
 *   ceiling (received - already returned) can be found for it.
 * - The unique index on (source, direction, mode, reason) makes a double tap harmless.
 *
 * See [com.example.easy_billing.util.MoneyMode] and friends for the allowed values.
 */
@Entity(
    tableName = "money_entries",
    indices = [
        Index(value = ["uid"], unique = true),
        Index(value = ["source_type", "source_id", "direction", "mode", "reason"], unique = true),
        Index(value = ["parent_type", "parent_id"]),
        Index(value = ["created_at"])
    ]
)
data class MoneyEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val uid: String,
    val direction: String,
    val mode: String,
    val amount: Double,
    val reason: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_id") val sourceId: Int,
    @ColumnInfo(name = "parent_type") val parentType: String,
    @ColumnInfo(name = "parent_id") val parentId: Int,
    @ColumnInfo(name = "party_name") val partyName: String = "",
    @ColumnInfo(name = "ref_text") val refText: String = "",
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = appNow(),
    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false
)

/** Total per mode, for the default refund split. */
data class ModeSum(val mode: String, val total: Double)

/** Which way the money of one source document moved (IN / OUT). */
data class SourceDirection(val sourceId: Int, val direction: String)

@Dao
interface MoneyEntryDao {

    /** Returns -1 when the row already exists (same uid, or same source/direction/mode/reason). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: MoneyEntry): Long

    @Query("SELECT * FROM money_entries WHERE is_synced = 0 ORDER BY id LIMIT :limit")
    suspend fun unsynced(limit: Int): List<MoneyEntry>

    @Query("UPDATE money_entries SET is_synced = 1 WHERE uid IN (:uids)")
    suspend fun markSynced(uids: List<String>)

    /** Fills in the bill / purchase number once the server has assigned it (descriptive text only). */
    @Query("UPDATE money_entries SET ref_text = :refText WHERE uid = :uid")
    suspend fun setRefText(uid: String, refText: String)

    @Query("SELECT uid FROM money_entries")
    suspend fun allUids(): List<String>

    @Query("SELECT COUNT(*) FROM money_entries")
    suspend fun count(): Int

    @Query("SELECT * FROM money_entries WHERE id = :id")
    suspend fun getById(id: Int): MoneyEntry?

    @Query("SELECT * FROM money_entries WHERE parent_type = :parentType AND parent_id = :parentId ORDER BY id")
    suspend fun forParent(parentType: String, parentId: Int): List<MoneyEntry>

    /** Total RECEIVED money in one direction for a bill / purchase (PENDING rows are not money yet). */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM money_entries " +
            "WHERE parent_type = :parentType AND parent_id = :parentId " +
            "AND direction = :direction AND status = 'RECEIVED'"
    )
    suspend fun sumForParent(parentType: String, parentId: Int, direction: String): Double

    /** Same as [sumForParent] but only for the given reasons (the refund ceiling ignores cash settlements). */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM money_entries " +
            "WHERE parent_type = :parentType AND parent_id = :parentId " +
            "AND direction = :direction AND status = 'RECEIVED' AND reason IN (:reasons)"
    )
    suspend fun sumForParentReasons(parentType: String, parentId: Int, direction: String, reasons: List<String>): Double

    @Query(
        "SELECT mode AS mode, COALESCE(SUM(amount), 0) AS total FROM money_entries " +
            "WHERE parent_type = :parentType AND parent_id = :parentId " +
            "AND direction = :direction AND status = 'RECEIVED' AND reason IN (:reasons) GROUP BY mode"
    )
    suspend fun sumByModeReasons(parentType: String, parentId: Int, direction: String, reasons: List<String>): List<ModeSum>

    /** UPI money of a bill that is still wanted (received or pending, not cancelled). Used for the pay link / QR amount. */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM money_entries WHERE source_type = :sourceType AND source_id = :sourceId " +
            "AND mode = 'UPI' AND direction = 'IN' AND reason = 'SALE' AND status != 'CANCELLED'"
    )
    suspend fun upiAmountForSource(sourceType: String, sourceId: Int): Double

    /** Direction of each listed document's money rows (one answer per document). Used to word credit history. */
    @Query(
        "SELECT source_id AS sourceId, direction AS direction FROM money_entries " +
            "WHERE source_type = :sourceType AND source_id IN (:ids) AND status != 'CANCELLED' GROUP BY source_id"
    )
    suspend fun directionsForSources(sourceType: String, ids: List<Int>): List<SourceDirection>

    /** The bill was cancelled before its pending (unpaid) money arrived: it will never be received. */
    @Query(
        "UPDATE money_entries SET status = 'CANCELLED', is_synced = 0 " +
            "WHERE parent_type = :parentType AND parent_id = :parentId AND status = 'PENDING' AND reason NOT LIKE '%\\_DUE' ESCAPE '\\'"
    )
    suspend fun cancelPending(parentType: String, parentId: Int): Int

    /** Rows in a time window, oldest first. Used by the report and the statement. */
    @Query("SELECT * FROM money_entries WHERE created_at >= :from AND created_at < :to ORDER BY created_at, id")
    suspend fun between(from: Long, to: Long): List<MoneyEntry>

    /** A UPI payment link was paid: the PENDING row becomes real money. */
    @Query(
        "UPDATE money_entries SET status = 'RECEIVED', is_synced = 0, " +
            "created_at = CASE WHEN :at > created_at THEN :at ELSE created_at END " +
            "WHERE source_type = :sourceType AND source_id = :sourceId AND mode = :mode AND status = 'PENDING' " +
            "AND reason NOT LIKE '%\\_DUE' ESCAPE '\\'"
    )
    suspend fun markReceived(sourceType: String, sourceId: Int, mode: String, at: Long): Int

    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM money_entries WHERE status = 'PENDING' AND direction = 'IN' " +
            "AND reason NOT LIKE '%\\_DUE' ESCAPE '\\'"
    )
    suspend fun pendingInTotal(): Double

    /** Open "money still due" notes of one bill / purchase, oldest first. */
    @Query(
        "SELECT * FROM money_entries WHERE parent_type = :parentType AND parent_id = :parentId " +
            "AND status = 'PENDING' AND reason LIKE '%\\_DUE' ESCAPE '\\' ORDER BY id"
    )
    suspend fun openDues(parentType: String, parentId: Int): List<MoneyEntry>

    /** Every open "money still due" note on any bill (Bill history marks those bills and filters them). */
    @Query(
        "SELECT * FROM money_entries WHERE parent_type = 'BILL' " +
            "AND status = 'PENDING' AND reason LIKE '%\\_DUE' ESCAPE '\\'"
    )
    suspend fun openBillDues(): List<MoneyEntry>

    /** Money still waiting to arrive (for example UPI not yet paid), any date, oldest first. Read only. */
    @Query(
        "SELECT * FROM money_entries WHERE status = 'PENDING' " +
            "AND reason NOT LIKE '%\\_DUE' ESCAPE '\\' ORDER BY created_at, id"
    )
    suspend fun allPendingMoney(): List<MoneyEntry>

    /** Every open "money still due" note (to give or to receive), any date, oldest first. Read only. */
    @Query(
        "SELECT * FROM money_entries WHERE status = 'PENDING' " +
            "AND reason LIKE '%\\_DUE' ESCAPE '\\' ORDER BY created_at, id"
    )
    suspend fun allOpenDues(): List<MoneyEntry>

    /** A due note was settled (or turned out to be nothing): it is no longer wanted. */
    @Query(
        "UPDATE money_entries SET status = 'CANCELLED', is_synced = 0 " +
            "WHERE source_type = :sourceType AND source_id = :sourceId AND reason IN (:dueReasons) AND status = 'PENDING'"
    )
    suspend fun closeDues(sourceType: String, sourceId: Int, dueReasons: List<String>): Int

    /** A bill / purchase was cancelled: its uncollected extra charge / extra payment is moot, so those notes are closed. */
    @Query(
        "UPDATE money_entries SET status = 'CANCELLED', is_synced = 0 " +
            "WHERE parent_type = :parentType AND parent_id = :parentId AND reason IN (:dueReasons) AND status = 'PENDING'"
    )
    suspend fun closeDuesForParent(parentType: String, parentId: Int, dueReasons: List<String>): Int

    /** The UPI money of a bill that has not arrived yet (PENDING is not money, so its amount may still change). */
    @Query(
        "SELECT * FROM money_entries WHERE parent_type = 'BILL' AND parent_id = :billId AND mode = 'UPI' " +
            "AND direction = 'IN' AND reason = 'SALE' AND status = 'PENDING' ORDER BY id"
    )
    suspend fun pendingUpiRows(billId: Int): List<MoneyEntry>

    /** Lowers (or closes) a still-pending row, e.g. after goods were returned before the customer paid. */
    @Query("UPDATE money_entries SET amount = :amount, status = :status, is_synced = 0 WHERE id = :id AND status = 'PENDING'")
    suspend fun reducePending(id: Int, amount: Double, status: String): Int

    /** RECEIVED UPI money of a bill's sale rows. */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM money_entries WHERE parent_type = 'BILL' AND parent_id = :billId " +
            "AND mode = 'UPI' AND direction = 'IN' AND reason = 'SALE' AND status = 'RECEIVED'"
    )
    suspend fun receivedUpiForBill(billId: Int): Double

    /** Next free source id for a manual / opening row (those have no document of their own). */
    @Query("SELECT COALESCE(MAX(source_id), 0) + 1 FROM money_entries WHERE source_type = 'MANUAL'")
    suspend fun nextManualSourceId(): Int

    /**
     * RECEIVED money per mode and direction in a window. [skipReasons] leaves reasons out
     * (OPENING and TRANSFER for earnings figures, an empty list to count everything).
     */
    @Query(
        "SELECT mode AS mode, direction AS direction, COALESCE(SUM(amount), 0) AS total FROM money_entries " +
            "WHERE status = 'RECEIVED' AND created_at >= :from AND created_at < :to AND reason NOT IN (:skipReasons) " +
            "GROUP BY mode, direction"
    )
    suspend fun sumsBetween(from: Long, to: Long, skipReasons: List<String>): List<ModeDirTotal>

    @Query("SELECT COUNT(*) FROM money_entries WHERE reason = :reason")
    suspend fun countReason(reason: String): Int

    /** Manual / opening / transfer rows written by one entry (a transfer has two legs). */
    @Query("SELECT * FROM money_entries WHERE source_type = 'MANUAL' AND source_id = :sourceId ORDER BY id")
    suspend fun forManual(sourceId: Int): List<MoneyEntry>

    /** Opening-balance rows that have not been reversed. Zero means the opening balance may be set (again). */
    @Query(
        "SELECT COUNT(*) FROM money_entries o WHERE o.reason = 'OPENING' AND o.direction = 'IN' AND NOT EXISTS (" +
            "SELECT 1 FROM money_entries r WHERE r.source_type = 'MANUAL' AND r.source_id = o.source_id " +
            "AND r.reason = 'OPENING' AND r.direction = 'OUT' AND r.mode = o.mode)"
    )
    suspend fun countActiveOpening(): Int
}
