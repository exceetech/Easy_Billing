package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "store_info")
data class StoreInfo(

    @PrimaryKey val id: Int = 1,

    val name: String,
    val address: String,
    val phone: String,
    val gstin: String,
    val type: String = "general",

    val legalName: String = "",
    val tradeName: String = "",
    val gstScheme: String = "",
    val stateCode: String = "",
    val registrationType: String = "",

    @ColumnInfo(name = "is_synced")
    var isSynced: Boolean = false
)