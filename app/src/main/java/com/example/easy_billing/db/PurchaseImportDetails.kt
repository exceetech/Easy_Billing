package com.example.easy_billing.db

import com.example.easy_billing.util.appNow

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "purchase_import_details",
    indices = [
        Index(value = ["purchase_id"]),
        Index(value = ["local_purchase_id"]),
        Index(value = ["sync_status"]),
        Index(value = ["bill_of_entry_number"])
    ]
)
data class PurchaseImportDetails(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "purchase_id")
    val purchaseId: Int,

    @ColumnInfo(name = "shop_id")
    val shopId: String = "",

    @ColumnInfo(name = "local_purchase_id")
    val localPurchaseId: Int,

    @ColumnInfo(name = "port_code")
    val portCode: String,

    @ColumnInfo(name = "bill_of_entry_number")
    val billOfEntryNumber: String,

    @ColumnInfo(name = "bill_of_entry_date")
    val billOfEntryDate: Long,

    @ColumnInfo(name = "bill_of_entry_value")
    val billOfEntryValue: Double = 0.0,

    @ColumnInfo(name = "document_type")
    val documentType: String = "Bill of Entry",

    @ColumnInfo(name = "sez_supplier_gstin")
    val sezSupplierGstin: String? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending", // pending, synced, failed

    @ColumnInfo(name = "device_id")
    val deviceId: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = appNow(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = appNow()
)
