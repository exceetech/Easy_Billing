package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CreditAccountDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: CreditAccount): Long

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

    @Query("""
        UPDATE credit_accounts 
        SET dueAmount = :amount 
        WHERE id = :id AND shopId = :shopId AND isActive = 1
    """)
    suspend fun updateDue(id: Int, amount: Double, shopId: Int)

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
}