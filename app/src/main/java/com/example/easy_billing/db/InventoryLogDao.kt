package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface InventoryLogDao {

    @Insert
    suspend fun insert(log: InventoryLog)

    // 🔥 all logs of product
    @Query("SELECT * FROM inventory_log WHERE productId = :productId ORDER BY date ASC")
    suspend fun getLogs(productId: Int): List<InventoryLog>

    // 🔥 total added
    @Query("""
        SELECT IFNULL(SUM(quantity),0) 
        FROM inventory_log 
        WHERE productId = :productId AND type = 'ADD'
    """)
    suspend fun getTotalAdded(productId: Int): Double

    // 🔥 total sold
    @Query("""
        SELECT IFNULL(SUM(quantity),0) 
        FROM inventory_log 
        WHERE productId = :productId AND type = 'SALE'
    """)
    suspend fun getTotalSold(productId: Int): Double

    // 🔥 total loss
    @Query("""
        SELECT IFNULL(SUM(quantity),0) 
        FROM inventory_log 
        WHERE productId = :productId AND type = 'LOSS'
    """)
    suspend fun getTotalLossQty(productId: Int): Double

    @Query("""
        SELECT SUM(quantity * price)
        FROM inventory_log
        WHERE type = 'ADD'
    """)
    suspend fun getTotalExpense(): Double?

    // Issue 13: explicit oldest-first order. The backend replays these logs
    // sequentially to rebuild its own stock count, and ADJUST entries carry
    // an ABSOLUTE stock count rather than a delta — replaying them even one
    // step out of true order can silently roll the server's stock back to a
    // stale value and discard real activity that happened after it. Without
    // this ORDER BY, row order was left to SQLite's default scan, which is
    // not a guaranteed contract.
    @Query("SELECT * FROM inventory_log WHERE isSynced = 0 ORDER BY id ASC")
    suspend fun getUnsyncedLogs(): List<InventoryLog>

    @Query("UPDATE inventory_log SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Int>)

    @Query("SELECT COUNT(*) FROM inventory_log WHERE productId = :productId AND isSynced = 0")
    suspend fun getUnsyncedCountForProduct(productId: Int): Int

    @Query("SELECT COUNT(*) FROM inventory_log WHERE isSynced = 0")
    suspend fun countUnsynced(): Int
}