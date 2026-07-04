package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ProductCategoryDao {

    /** Insert; ignore if (shop_id, name) already exists. Returns rowId or -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: ProductCategory): Long

    /** Alias matching the spec naming. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(category: ProductCategory): Long

    @Update
    suspend fun update(category: ProductCategory)

    @Delete
    suspend fun delete(category: ProductCategory)

    @Query("SELECT * FROM product_categories WHERE shop_id = :shopId AND isActive = 1 ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllForShop(shopId: String): List<ProductCategory>

    @Query("SELECT * FROM product_categories WHERE shop_id = :shopId AND name = :name LIMIT 1")
    suspend fun getByName(name: String, shopId: String): ProductCategory?

    @Query("SELECT * FROM product_categories WHERE serverId IS NULL AND isActive = 1")
    suspend fun getUnsynced(): List<ProductCategory>

    @Query("UPDATE product_categories SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: Int, serverId: Int)
}
