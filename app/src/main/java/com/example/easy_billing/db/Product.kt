package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "products")
data class Product(
    @PrimaryKey(autoGenerate = false)
    val id: Int = 0,

    val name: String,

    val variant: String?,
    val unit: String?,

    val price: Double,
    val isCustom: Boolean = false
) : Serializable