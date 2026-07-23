package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// INV-4 fix: one product = one inventory row, enforced by the DB rather
// than by convention. Without this, nothing stopped a future bug from
// producing two rows for the same productId, which InventoryDao.getAll()
// (unfiltered) would then surface as two separate stock/avg-cost numbers
// for what should be a single product.
@Entity(tableName = "inventory", indices = [Index(value = ["productId"], unique = true)])
data class Inventory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val productId: Int,
    val currentStock: Double,
    val averageCost: Double,
    val isActive: Boolean = true,
    val isSynced: Boolean = false
)