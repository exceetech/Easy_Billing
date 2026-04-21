package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ProductDao {

    @Insert
    suspend fun insert(product: Product): Long

    @Query("SELECT * FROM products ORDER BY name ASC")
    suspend fun getAll(): List<Product>

    @Query("DELETE FROM products")
    suspend fun deleteAll()

    @Query("DELETE FROM products WHERE id = :productId")
    suspend fun deleteById(productId: Int)

    @Query("""
SELECT * FROM products 
WHERE name = :name 
AND (:variant IS NULL OR variant = :variant)
LIMIT 1
""")
    suspend fun getByNameAndVariant(name: String, variant: String?): Product?
}