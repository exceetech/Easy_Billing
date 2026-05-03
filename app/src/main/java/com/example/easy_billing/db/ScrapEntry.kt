package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stock written off as scrap / damage (`scrap_table`).
 *
 * Same shape as [PurchaseReturn] (purchase-side tax) so both can
 * feed the same accounting / reporting pipelines.
 */
@Entity(
    tableName = "scrap_table",
    indices = [
        Index(value = ["productId"]),
        Index(value = ["hsnCode"])
    ]
)
data class ScrapEntry(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val productId: Int? = null,
    val productName: String,
    val hsnCode: String? = null,

    val quantity: Double,
    val taxableAmount: Double,
    val invoiceValue: Double,

    @ColumnInfo(name = "cgst_percentage") val cgstPercentage: Double = 0.0,
    @ColumnInfo(name = "sgst_percentage") val sgstPercentage: Double = 0.0,
    @ColumnInfo(name = "igst_percentage") val igstPercentage: Double = 0.0,
    @ColumnInfo(name = "cgst_amount")     val cgstAmount: Double = 0.0,
    @ColumnInfo(name = "sgst_amount")     val sgstAmount: Double = 0.0,
    @ColumnInfo(name = "igst_amount")     val igstAmount: Double = 0.0,

    val reason: String = "Scrap",

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_synced")  val isSynced: Boolean = false
)
