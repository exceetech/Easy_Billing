package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stock returned to a supplier (`purchase_return_table`).
 *
 * Tax fields here mirror the *purchase* tax (i.e. what was
 * originally paid to the supplier) since a return reverses the
 * earlier purchase, not a sale.
 */
@Entity(
    tableName = "purchase_return_table",
    indices = [
        Index(value = ["productId"]),
        Index(value = ["hsnCode"])
    ]
)
data class PurchaseReturn(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val productId: Int? = null,
    val productName: String,
    val hsnCode: String? = null,

    val quantityReturned: Double,
    val taxableAmount: Double,
    val invoiceValue: Double,

    @ColumnInfo(name = "cgst_percentage") val cgstPercentage: Double = 0.0,
    @ColumnInfo(name = "sgst_percentage") val sgstPercentage: Double = 0.0,
    @ColumnInfo(name = "igst_percentage") val igstPercentage: Double = 0.0,
    @ColumnInfo(name = "cgst_amount")     val cgstAmount: Double = 0.0,
    @ColumnInfo(name = "sgst_amount")     val sgstAmount: Double = 0.0,
    @ColumnInfo(name = "igst_amount")     val igstAmount: Double = 0.0,

    val supplierGstin: String? = null,
    val supplierName: String? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_synced")  val isSynced: Boolean = false
)
