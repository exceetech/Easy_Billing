package com.example.easy_billing.db

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
    val total: Double
)