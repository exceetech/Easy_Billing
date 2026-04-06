package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CreditTransactionDao {

    @Insert
    suspend fun insert(txn: CreditTransaction)

    @Query("SELECT * FROM credit_transactions WHERE isSynced = 0")
    suspend fun getUnsynced(): List<CreditTransaction>

    @Query("UPDATE credit_transactions SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: Int)
}