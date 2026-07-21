package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface CreditAccountDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: CreditAccount): Long

    /**
     * Updates an existing row by primary key.
     *
     * [insert] is declared IGNORE-on-conflict, so passing it a row that already
     * exists does nothing at all — silently. The server pull used insert() to
     * refresh accounts, which meant a balance changed on another terminal could
     * never reach this one. Refreshes must go through here.
     */
    @Update
    suspend fun update(account: CreditAccount)

    @Query("""
        SELECT * FROM credit_accounts 
        WHERE isActive = 1 AND shopId = :shopId
    """)
    suspend fun getAll(shopId: Int): List<CreditAccount>

    @Query("""
        SELECT * FROM credit_accounts 
        WHERE isActive = 1 
        AND shopId = :shopId
        AND (name LIKE :query OR phone LIKE :query)
    """)
    suspend fun search(query: String, shopId: Int): List<CreditAccount>

    /**
     * Sets the balance to an exact figure. Correct only when the new value does
     * not depend on the old one — settling to zero, or accepting the server's
     * figure during a pull. For anything that *adjusts* a balance, use
     * [addToDue] instead.
     */
    @Query("""
        UPDATE credit_accounts
        SET dueAmount = :amount
        WHERE id = :id AND shopId = :shopId AND isActive = 1
    """)
    suspend fun updateDue(id: Int, amount: Double, shopId: Int)

    /**
     * Applies a change to the balance in the database itself.
     *
     * The callers used to read the account, compute `dueAmount ± something` in
     * Kotlin, and write the total back. In InvoiceActivity the account is
     * captured when the customer is picked and written when the bill is saved —
     * minutes apart in a real billing session — so anything that moved the
     * balance in between was silently overwritten. That became reachable once
     * the server pull started updating existing accounts.
     *
     * Adding the delta in SQL means the database applies it to whatever the
     * value is at that moment, so no read can go stale.
     */
    @Query("""
        UPDATE credit_accounts
        SET dueAmount = dueAmount + :delta
        WHERE id = :id AND shopId = :shopId AND isActive = 1
    """)
    suspend fun addToDue(id: Int, delta: Double, shopId: Int)

    @Query("""
        UPDATE credit_accounts 
        SET isSynced = 1 
        WHERE id = :id AND shopId = :shopId
    """)
    suspend fun markSynced(id: Int, shopId: Int)

    @Query("""
        SELECT * FROM credit_accounts 
        WHERE id = :id AND shopId = :shopId AND isActive = 1
    """)
    suspend fun getById(id: Int, shopId: Int): CreditAccount?

    @Query("""
        SELECT * FROM credit_accounts 
        WHERE phone = :phone 
        AND shopId = :shopId 
        LIMIT 1
    """)
    suspend fun getByPhone(phone: String, shopId: Int): CreditAccount?

    @Query("""
        SELECT * FROM credit_accounts 
        WHERE serverId = :serverId AND shopId = :shopId 
        LIMIT 1
    """)
    suspend fun getByServerId(serverId: Int, shopId: Int): CreditAccount?

    @Query("""
        UPDATE credit_accounts
        SET isActive = 0
        WHERE id = :id AND shopId = :shopId
    """)
    suspend fun deactivate(id: Int, shopId: Int)

    /**
     * Propagate server-side deletions (Sync audit S6): deactivate any locally
     * server-known account that is no longer in the server's active set. Only
     * touches rows with a serverId (never pending local-only accounts), so an
     * unsynced new account isn't wiped. Caller must pass a non-empty list.
     */
    @Query("""
        UPDATE credit_accounts
        SET isActive = 0
        WHERE shopId = :shopId
          AND serverId IS NOT NULL
          AND isActive = 1
          AND serverId NOT IN (:activeServerIds)
    """)
    suspend fun deactivateMissing(shopId: Int, activeServerIds: List<Int>)

    @Query("""
    UPDATE credit_accounts
    SET 
        serverId = :serverId,
        isSynced = 1,
        isActive = 1
    WHERE id = :localId AND shopId = :shopId
""")
    suspend fun updateServerId(
        localId: Int,
        serverId: Int,
        shopId: Int
    )

    @Query("""
    UPDATE credit_accounts
    SET 
        isActive = 1,
        name = :name,
        isSynced = :isSynced
    WHERE phone = :phone AND shopId = :shopId
""")
    suspend fun restoreAccount(
        phone: String,
        name: String,
        isSynced: Boolean,
        shopId: Int
    )
}