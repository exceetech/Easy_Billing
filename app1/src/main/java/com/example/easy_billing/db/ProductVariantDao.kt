package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProductVariantDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(variant: ProductVariant)

    @Query("SELECT * FROM product_variants WHERE productId = :productId")
    suspend fun getVariants(productId: Int): List<ProductVariant>

    @Query("DELETE FROM product_variants")
    suspend fun deleteAll()
}