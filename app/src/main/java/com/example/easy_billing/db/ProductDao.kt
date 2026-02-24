package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.easy_billing.Product


@Dao
interface ProductDao {

    @Insert
    suspend fun insert(product: Product)

    @Query("SELECT * FROM products ORDER BY name ASC")
    suspend fun getAll(): List<Product>

    @Query("DELETE FROM products")
    suspend fun deleteAll()

    @Query("DELETE FROM products WHERE id = :productId")
    suspend fun deleteById(productId: Int)
}