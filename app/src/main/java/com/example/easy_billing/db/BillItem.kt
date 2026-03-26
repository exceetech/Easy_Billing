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
    val price: Double,
    val quantity: Int,
    val subTotal: Double,

    @ColumnInfo(name = "is_synced")
    var isSynced: Boolean = false
)