package com.example.easy_billing

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DefaultProductDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(products: List<DefaultProduct>)

    @Query("SELECT * FROM default_products ORDER BY name ASC")
    suspend fun getAll(): List<DefaultProduct>

    @Query("SELECT * FROM default_products WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    suspend fun search(query: String): List<DefaultProduct>
}