package com.example.easy_billing.db

import androidx.room.*

@Dao
interface InventoryDao {

    @Query("SELECT * FROM inventory WHERE productId = :productId LIMIT 1")
    suspend fun getInventory(productId: Int): Inventory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(inventory: Inventory)

    @Update
    suspend fun update(inventory: Inventory)

    @Query("SELECT * FROM inventory")
    suspend fun getAll(): List<Inventory>

    // 🔥 SAFE REDUCE
    @Query("""
        UPDATE inventory 
        SET currentStock = currentStock - :qty,
            isSynced = 0
        WHERE productId = :productId 
        AND currentStock >= :qty
    """)
    suspend fun reduceStock(productId: Int, qty: Double): Int

    // 🔥 INCREASE
    @Query("""
        UPDATE inventory 
        SET currentStock = currentStock + :qty,
            isSynced = 0
        WHERE productId = :productId
    """)
    suspend fun increaseStock(productId: Int, qty: Double)

    // 🔥 CLEAR
    @Query("""
        UPDATE inventory 
        SET currentStock = 0,
            isSynced = 0
        WHERE productId = :productId
    """)
    suspend fun clearStock(productId: Int)

    @Query("SELECT SUM(currentStock) FROM inventory WHERE productId = :productId")
    suspend fun getTotalQuantity(productId: Int): Double?
}