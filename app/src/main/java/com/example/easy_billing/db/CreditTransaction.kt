package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "credit_transactions")
data class CreditTransaction(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val accountId: Int,

    val shopId: Int,

    val amount: Double,
    val type: String, // ADD / PAY / SETTLE

    val timestamp: Long = System.currentTimeMillis(),

    val isSynced: Boolean = false
)