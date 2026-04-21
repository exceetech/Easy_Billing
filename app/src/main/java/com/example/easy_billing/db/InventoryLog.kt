package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inventory_log")
data class InventoryLog(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val productId: Int,

    val type: String,
    // "ADD", "SALE", "LOSS", "ADJUST"

    val quantity: Double,

    val price: Double,
    // cost price for ADD/LOSS, selling price for SALE

    val date: Long
)