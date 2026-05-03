package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One record per purchase invoice or expense entry with full GST breakdown.
 * Used for GSTR-2 (purchase register) and ITC computation in GSTR-3B.
 */
@Entity(tableName = "gst_purchase_records")
data class GstPurchaseRecord(

    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    val vendorGstin: String? = null,
    val vendorName: String? = null,
    val invoiceNumber: String,
    val invoiceDate: Long,            // epoch millis

    val totalInvoiceValue: Double,
    val taxableValue: Double,
    val gstRate: Double,
    val cgstAmount: Double = 0.0,
    val sgstAmount: Double = 0.0,
    val igstAmount: Double = 0.0,
    val cessAmount: Double = 0.0,

    val hsnCode: String,
    val itcEligibility: String = "Eligible",

    // Optional fields for broader compatibility
    val expenseType: String = "STOCK",
    val description: String = "",

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending",

    val deviceId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
