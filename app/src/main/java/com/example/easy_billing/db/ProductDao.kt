package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ProductDao {

    @Insert
    suspend fun insert(product: Product): Long

    @Update
    suspend fun update(product: Product): Int

    // 🔥 ONLY ACTIVE PRODUCTS
    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY name ASC")
    suspend fun getAll(): List<Product>

    // 🔥 INCLUDE INACTIVE (for restore logic)
    @Query("SELECT * FROM products ORDER BY name ASC")
    suspend fun getAllWithInactive(): List<Product>

    // ❌ REMOVE HARD DELETE (VERY IMPORTANT)
    // @Query("DELETE FROM products WHERE id = :productId")

    // 🔥 SOFT DELETE
    @Query("UPDATE products SET isActive = 0 WHERE id = :productId")
    suspend fun deactivate(productId: Int)

    // 🔥 RESTORE
    @Query("UPDATE products SET isActive = 1 WHERE id = :productId")
    suspend fun activate(productId: Int)

    // 🔥 STRICT MATCH (VERY IMPORTANT FIX)
    @Query("""
        SELECT * FROM products 
        WHERE name = :name 
        AND (
            (variant IS NULL AND :variant IS NULL)
            OR variant = :variant
        )
        LIMIT 1
    """)
    suspend fun getByNameAndVariant(name: String, variant: String?): Product?

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Product?

    @Query("SELECT * FROM products WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: Int): Product?
}