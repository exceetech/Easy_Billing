package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bill_items")
data class BillItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val billId: Int,
    val productId: Int,
    val productName: String,
    val variant: String?,
    val unit: String,
    val price: Double,
    val quantity: Double,
    val subTotal: Double,

    val costPriceUsed: Double = 0.0,
    val profit: Double = 0.0,

    @ColumnInfo(name = "is_synced")
    var isSynced: Boolean = false
)