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

    // ORDER BY id DESC so the most-recently synced bill wins when
    // Room DB has stale duplicates from a previous server session
    // that happened to reuse the same bill number after a DB wipe.
    @Query("SELECT * FROM bills WHERE billNumber = :billNumber ORDER BY id DESC LIMIT 1")
    suspend fun getByBillNumber(billNumber: String): Bill?

    // Clears a stale bill number from any Room DB bill OTHER than the
    // canonical one (exceptId). Called after syncBills() assigns a fresh
    // bill_number so old bills from previous server sessions stop matching
    // the getByBillNumber lookup and polluting SalesReturn/DebitNote.
    @Query("UPDATE bills SET billNumber = '' WHERE billNumber = :billNumber AND id != :exceptId")
    suspend fun clearDuplicateBillNumbers(billNumber: String, exceptId: Int)

    @Query("DELETE FROM bills")
    suspend fun deleteAllBills()

    @Query("DELETE FROM bill_items")
    suspend fun deleteAllItems()

    @Query("SELECT * FROM bills WHERE is_synced = 0")
    suspend fun getUnsyncedBills(): List<Bill>

    @Query("SELECT COUNT(*) FROM bills WHERE is_synced = 0")
    suspend fun countUnsynced(): Int

    @Query("UPDATE bills SET billNumber = :billNumber WHERE id = :billId")
    fun updateBillNumber(billId: Int, billNumber: String)

    @Query("UPDATE bills SET is_synced = 1 WHERE id = :id")
    suspend fun markBillSynced(id: Int)

    // ===== Cancellation (v23) =====

    // INV-8 fix: conditioned on is_cancelled = 0 and returning the affected
    // row count turns this into an atomic check-and-set at the data layer.
    // The previous version unconditionally set is_cancelled = 1 no matter
    // what it was before, so the only thing standing between a single
    // cancellation and a double one was BillDetailsActivity disabling its
    // button — pure UI state, which a rotated screen, a process restart, or
    // two near-simultaneous taps could all bypass. Now the caller can tell,
    // from the DB itself, whether this call was the one that actually
    // cancelled the bill (returns 1) or whether it was already cancelled
    // (returns 0) — and only restock inventory in the first case.
    @Query("""
        UPDATE bills
        SET is_cancelled = 1, cancelled_at = :cancelledAt
        WHERE id = :billId AND is_cancelled = 0
    """)
    suspend fun markBillCancelled(billId: Int, cancelledAt: Long): Int

    @Query("SELECT is_cancelled FROM bills WHERE id = :billId LIMIT 1")
    suspend fun isCancelled(billId: Int): Boolean?

    // Deep-dive fix, Issue 5: used to exclude a cancelled bill's own
    // credit/debit notes from the in-app GSTR-1 report — see
    // Gstr1Repository.fetchForPeriod.
    @Query("SELECT id FROM bills WHERE is_cancelled = 1")
    suspend fun getCancelledBillIds(): List<Int>

    // N3: only voids the server hasn't acknowledged yet — once
    // cancel_synced is set, the bill drops out of the sync loop.
    @Query("SELECT * FROM bills WHERE is_cancelled = 1 AND cancel_synced = 0")
    suspend fun getCancelledBills(): List<Bill>

    @Query("UPDATE bills SET cancel_synced = 1 WHERE id = :billId")
    suspend fun markCancelSynced(billId: Int)

    // ===== Profit-analytics pulse (Report 5 fix) =====

    // Only bills that already have a real server bill number (is_synced=1)
    // AND items (billId join) are eligible — a bill with no number yet will
    // get its pulse pushed for the first time by syncBills() itself; this
    // backfill query is for bills whose FIRST attempt (fire-and-forget, in
    // InvoiceActivity) already failed silently.
    @Query("SELECT * FROM bills WHERE is_synced = 1 AND sale_pulse_synced = 0")
    suspend fun getBillsNeedingSalePulse(): List<Bill>

    @Query("UPDATE bills SET sale_pulse_synced = 1 WHERE id = :billId")
    suspend fun markSalePulseSynced(billId: Int)
}