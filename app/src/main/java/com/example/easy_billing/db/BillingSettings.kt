package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "billing_settings")
data class BillingSettings(

    @PrimaryKey val id: Int = 1,

    val defaultGst: Float,
    val printerLayout: String
)