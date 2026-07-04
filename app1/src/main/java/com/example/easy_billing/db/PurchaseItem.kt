package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single line on a purchase invoice (`purchase_items_table`).
 *
 * Stores **two** separate tax breakdowns:
 *
 *   • Purchase tax (`purchase_*`)  — what the shop paid the
 *     supplier, taken from the invoice itself.
 *   • Sales tax    (`sales_*`)     — what the shop will charge when
 *     reselling the same item; entered by the user while adding the
 *     product. These are mirrored into [Product] so future sales
 *     re-use them automatically.
 *
 * `cost_price` is computed as `invoiceValue / quantity` for
 * downstream margin/profit calculations. We persist both fields so
 * we never have to recompute on the read path.
 */
@Entity(
    tableName = "purchase_items_table",
    foreignKeys = [
        ForeignKey(
            entity = Purchase::class,
            parentColumns = ["id"],
            childColumns = ["purchaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["purchaseId"]),
        Index(value = ["hsnCode"])
    ]
)
data class PurchaseItem(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val purchaseId: Int,

    // Local product reference (nullable — the row may have been
    // entered before the product was created if the upsert failed).
    val productId: Int? = null,

    val productName: String,
    val variant: String? = null,
    val hsnCode: String? = null,

    val quantity: Double,
    val unit: String? = null,

    val taxableAmount: Double,
    val invoiceValue: Double,

    /** Convenience — `invoiceValue / quantity`. */
    @ColumnInfo(name = "cost_price")
    val costPrice: Double,

    @ColumnInfo(name = "discount_amount")
    val discountAmount: Double = 0.0,

    /* ---------- Purchase tax (paid to supplier) ---------- */

    @ColumnInfo(name = "purchase_cgst_percentage") val purchaseCgstPercentage: Double = 0.0,
    @ColumnInfo(name = "purchase_sgst_percentage") val purchaseSgstPercentage: Double = 0.0,
    @ColumnInfo(name = "purchase_igst_percentage") val purchaseIgstPercentage: Double = 0.0,
    @ColumnInfo(name = "purchase_cgst_amount")     val purchaseCgstAmount: Double = 0.0,
    @ColumnInfo(name = "purchase_sgst_amount")     val purchaseSgstAmount: Double = 0.0,
    @ColumnInfo(name = "purchase_igst_amount")     val purchaseIgstAmount: Double = 0.0,

    /* ---------- Sales tax (will be charged to customer) ---------- */

    @ColumnInfo(name = "sales_cgst_percentage") val salesCgstPercentage: Double = 0.0,
    @ColumnInfo(name = "sales_sgst_percentage") val salesSgstPercentage: Double = 0.0,
    @ColumnInfo(name = "sales_igst_percentage") val salesIgstPercentage: Double = 0.0,

    @ColumnInfo(name = "cess_percentage") val cessPercentage: Double = 0.0,
    @ColumnInfo(name = "cess_amount") val cessAmount: Double = 0.0,
    @ColumnInfo(name = "eligibility_for_itc") val eligibilityForItc: String = "Inputs",
    @ColumnInfo(name = "availed_itc_igst") val availedItcIgst: Double = 0.0,
    @ColumnInfo(name = "availed_itc_cgst") val availedItcCgst: Double = 0.0,
    @ColumnInfo(name = "availed_itc_sgst") val availedItcSgst: Double = 0.0,
    @ColumnInfo(name = "availed_itc_cess") val availedItcCess: Double = 0.0,
    @ColumnInfo(name = "hsn_description") val hsnDescription: String = "",
    @ColumnInfo(name = "official_uqc") val officialUqc: String = "",
    @ColumnInfo(name = "supply_classification") val supplyClassification: String = "TAXABLE",

    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false
)
