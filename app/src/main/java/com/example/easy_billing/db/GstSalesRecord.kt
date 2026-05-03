package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One record per bill line-item with full GST breakdown.
 * Written atomically at bill creation time.
 * Used for GSTR-1, GSTR-3B, and HSN Summary reports.
 * Reports NEVER query raw bill_items — always query this table.
 */
@Entity(tableName = "gst_sales_records")
data class GstSalesRecord(

    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    val invoiceNumber: String,
    val invoiceDate: Long,             // epoch millis

    // Customer
    val customerType: String,          // B2B / B2C
    val customerGstin: String? = null, // only for B2B

    // Supply
    val placeOfSupply: String,         // 2-digit state code
    val supplyType: String,            // intrastate / interstate

    // Product
    val hsnCode: String,
    val productName: String,
    val quantity: Double,
    val unit: String = "piece",

    // Tax
    val taxableValue: Double,
    val gstRate: Double,               // e.g. 18.0
    val cgstAmount: Double = 0.0,
    val sgstAmount: Double = 0.0,
    val igstAmount: Double = 0.0,
    val totalAmount: Double,

    // Sync
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending",

    val deviceId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
