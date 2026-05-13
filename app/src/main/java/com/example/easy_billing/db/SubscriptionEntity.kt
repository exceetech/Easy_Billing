package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscription")
data class SubscriptionEntity(

    @PrimaryKey val id: Int = 1,

    val status: String,
    val expiryDate: Long,   // timestamp
    val lastSyncTime: Long
)