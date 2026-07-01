package com.example.easy_billing

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "default_products")
data class DefaultProduct(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String
)