package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import com.example.easy_billing.Product

@Dao
interface ProductDao {

    @Insert
    suspend fun insert(product: Product)

    @Query("SELECT * FROM products")
    suspend fun getAllProducts(): List<Product>

    @Database(entities = [Product::class], version = 1)
    abstract class AppDatabase : RoomDatabase() {
        abstract fun productDao(): ProductDao
    }
}