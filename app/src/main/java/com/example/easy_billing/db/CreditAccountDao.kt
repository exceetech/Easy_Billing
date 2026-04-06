package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CreditAccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: CreditAccount): Long

    @Query("SELECT * FROM credit_accounts")
    suspend fun getAll(): List<CreditAccount>

    @Query("SELECT * FROM credit_accounts WHERE name LIKE :query OR phone LIKE :query")
    suspend fun search(query: String): List<CreditAccount>

    @Query("UPDATE credit_accounts SET dueAmount = :amount WHERE id = :id")
    suspend fun updateDue(id: Int, amount: Double)

    @Query("UPDATE credit_accounts SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: Int)

    @Query("SELECT * FROM credit_accounts WHERE id = :id")
    suspend fun getById(id: Int): CreditAccount?

    @Query("SELECT * FROM credit_accounts WHERE phone = :phone LIMIT 1")
    suspend fun getByPhone(phone: String): CreditAccount?
}