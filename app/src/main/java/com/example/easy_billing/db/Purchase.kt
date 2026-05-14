package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A purchase invoice header (`purchase_table`).
 *
 * Captures the supplier, totals, and the supplier-side tax that the
 * shop *paid*. Per-line tax breakdown lives on [PurchaseItem].
 */
@Entity(
    tableName = "purchase_table",
    indices = [Index(value = ["invoiceNumber"])]
)
data class Purchase(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val invoiceNumber: String,
    val supplierGstin: String?,
    val supplierName: String,
    val state: String,

    // Aggregate purchase-side tax (header level)
    val taxableAmount: Double,
    @ColumnInfo(name = "cgst_percentage") val cgstPercentage: Double = 0.0,
    @ColumnInfo(name = "sgst_percentage") val sgstPercentage: Double = 0.0,
    @ColumnInfo(name = "igst_percentage") val igstPercentage: Double = 0.0,
    @ColumnInfo(name = "cgst_amount")     val cgstAmount: Double = 0.0,
    @ColumnInfo(name = "sgst_amount")     val sgstAmount: Double = 0.0,
    @ColumnInfo(name = "igst_amount")     val igstAmount: Double = 0.0,

    val invoiceValue: Double,

    /**
     * Date printed on the supplier's invoice — independent of
     * [createdAt] (when the user keyed it in). Stored as epoch
     * milliseconds at midnight UTC for the selected calendar day.
     * Nullable so existing rows that pre-date this column stay
     * valid after the v18→v19 migration.
     */
    @ColumnInfo(name = "invoice_date") val invoiceDate: Long? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_synced")  val isSynced: Boolean = false,
    @ColumnInfo(name = "server_id")  val serverId: Int? = null,

    // Credit Integration
    @ColumnInfo(name = "is_credit")          val isCredit: Boolean = false,
    @ColumnInfo(name = "credit_account_id")   val creditAccountId: Int? = null,
    @ColumnInfo(name = "credit_transaction_id") val creditTransactionId: Int? = null
)
