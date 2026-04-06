package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "credit_accounts",
    indices = [Index(value = ["phone"], unique = true)]  // ✅ ADD THIS
)
data class CreditAccount(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val serverId: Int? = null,
    val name: String,
    val phone: String,
    val dueAmount: Double = 0.0,
    val isSynced: Boolean = false
)