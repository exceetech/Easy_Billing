package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "products")
data class Product(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val name: String,
    val variant: String?,
    val unit: String?,
    val price: Double,

    val serverId: Int? = null,

    val trackInventory: Boolean,
    val isCustom: Boolean = false,
    val isActive: Boolean = true

) : Serializable