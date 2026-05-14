package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stock returned to a supplier (`purchase_return_table`).
 *
 * Fields mirror the backend `purchase_return` SQLAlchemy model
 * 1:1 so the sync push is a straight copy. `shop_id` and `state`
 * are populated from the local store_info / gst_profile at insert
 * time (see [com.example.easy_billing.repository.InventoryReductionRepository]).
 */
@Entity(
    tableName = "purchase_return_table",
    indices = [
        Index(value = ["productId"]),
        Index(value = ["hsnCode"]),
        Index(value = ["shop_id"])
    ]
)
data class PurchaseReturn(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "shop_id")
    val shopId: String = "",

    val productId: Int? = null,
    val productName: String,
    val variantName: String? = null,
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

    val state: String = "",

    val supplierGstin: String? = null,
    val supplierName: String? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_synced")  val isSynced: Boolean = false,

    // Credit Integration
    @ColumnInfo(name = "is_credit")          val isCredit: Boolean = false,
    @ColumnInfo(name = "credit_account_id")   val creditAccountId: Int? = null,
    @ColumnInfo(name = "credit_transaction_id") val creditTransactionId: Int? = null
)
