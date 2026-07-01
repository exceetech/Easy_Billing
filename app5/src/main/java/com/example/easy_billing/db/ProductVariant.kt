package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "product_variants")
data class ProductVariant(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val productId: Int,
    val variantName: String,
    val price: Double,
    val unit: String   // "PIECE" or "KG"
)