package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CreditTransactionDao {

    // ================= INSERT =================
    @Insert
    suspend fun insert(txn: CreditTransaction)


    /**
     * Pending transactions, oldest first.
     *
     * The ORDER BY is not cosmetic. Most types are adjustments and the server
     * adds them, so order wouldn't matter — but SETTLE *sets* the balance to
     * zero. Once one row in the queue resets rather than adjusts, replaying
     * them out of order changes the result: a sale sent after a settle puts
     * the debt back on an account that was cleared.
     *
     * Without ORDER BY, SQLite is free to return any order. It usually gives
     * insert order, which is why this held — but that isn't a guarantee, and
     * this is money.
     */
    @Query("""
        SELECT * FROM credit_transactions
        WHERE isSynced = 0
        AND shopId = :shopId
        ORDER BY id ASC
    """)
    suspend fun getUnsynced(shopId: Int): List<CreditTransaction>


    // ================= MARK SYNCED =================
    @Query("""
        UPDATE credit_transactions 
        SET isSynced = 1 
        WHERE id = :id AND shopId = :shopId
    """)
    suspend fun markSynced(id: Int, shopId: Int)


    /**
     * How many transactions on this account have not reached the server yet.
     *
     * The local balance already includes them; the server's does not. So the
     * pull must leave such an account alone — overwriting its balance with the
     * server's would silently discard a payment taken offline.
     */
    @Query("""
        SELECT COUNT(*) FROM credit_transactions
        WHERE accountId = :accountId AND shopId = :shopId AND isSynced = 0
    """)
    suspend fun countUnsyncedForAccount(accountId: Int, shopId: Int): Int


    // ================= GET BY ACCOUNT =================
    @Query("""
        SELECT * FROM credit_transactions 
        WHERE accountId = :id 
        AND shopId = :shopId
        ORDER BY id DESC
    """)
    suspend fun getByAccount(id: Int, shopId: Int): List<CreditTransaction>
}