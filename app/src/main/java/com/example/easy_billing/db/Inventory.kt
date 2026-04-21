package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inventory")
data class Inventory(

    @PrimaryKey
    val productId: Int,

    val currentStock: Double,
    val averageCost: Double,

    val isActive: Boolean = true,
    val isSynced: Boolean = false
)