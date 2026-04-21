package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inventory_transactions")
data class InventoryTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val productId: Int,
    val type: String,

    val quantity: Double,
    val costPrice: Double,
    val totalCost: Double,

    val createdAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)