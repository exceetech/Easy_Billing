package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "loss_table")
data class LossEntry(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val productId: Int,
    val amount: Double,
    val reason: String,
    val date: String
)