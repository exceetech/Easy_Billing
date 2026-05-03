package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bills")
data class Bill(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val billNumber: String,
    val date: String,
    val subTotal: Double,
    val gst: Double,
    val discount: Double,
    val total: Double,
    val paymentMethod: String,

    val customerType: String = "B2C",
    val customerGstin: String? = null,
    val placeOfSupply: String = "",
    val supplyType: String = "intrastate",
    val cgstAmount: Double = 0.0,
    val sgstAmount: Double = 0.0,
    val igstAmount: Double = 0.0,

    @ColumnInfo(name = "is_synced")
    var isSynced: Boolean = false
)