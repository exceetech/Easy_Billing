package com.example.easy_billing.db

import com.example.easy_billing.util.appNow

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "credit_transactions")
data class CreditTransaction(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val accountId: Int,

    val shopId: Int,

    val amount: Double,
    val type: String, // ADD / PAY / SETTLE / PURCHASE_CREDIT / PURCHASE_RETURN

    val referenceInvoice: String? = null,

    val timestamp: Long = appNow(),

    val isSynced: Boolean = false
)