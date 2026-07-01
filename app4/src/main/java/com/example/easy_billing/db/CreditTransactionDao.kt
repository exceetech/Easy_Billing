package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CreditTransactionDao {

    // ================= INSERT =================
    @Insert
    suspend fun insert(txn: CreditTransaction)


    // ================= GET UNSYNCED =================
    @Query("""
        SELECT * FROM credit_transactions 
        WHERE isSynced = 0 
        AND shopId = :shopId
    """)
    suspend fun getUnsynced(shopId: Int): List<CreditTransaction>


    // ================= MARK SYNCED =================
    @Query("""
        UPDATE credit_transactions 
        SET isSynced = 1 
        WHERE id = :id AND shopId = :shopId
    """)
    suspend fun markSynced(id: Int, shopId: Int)


    // ================= GET BY ACCOUNT =================
    @Query("""
        SELECT * FROM credit_transactions 
        WHERE accountId = :id 
        AND shopId = :shopId
        ORDER BY id DESC
    """)
    suspend fun getByAccount(id: Int, shopId: Int): List<CreditTransaction>
}