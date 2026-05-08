package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per line-item on a GST sales invoice.
 *
 *   • Linked to the parent via [gstInvoiceId] — *not* `billId`
 *     directly. The legacy `bill_items` table still holds the
 *     cost-of-goods + profit + inventory-bound view of the same
 *     line so reports / inventory keep working untouched.
 *
 *   • All four monetary fields ([taxableAmount], the three GST
 *     amounts, [netValue]) are pre-rounded to 2 decimals by
 *     [com.example.easy_billing.util.GstBillingCalculator].
 *     Down-stream code can sum them without further rounding.
 *
 *   • For Composition Scheme rows the three GST percentages and
 *     the three GST amounts are all zero, and [netValue] equals
 *     [taxableAmount] equals quantity × sellingPrice.
 */
@Entity(
    tableName = "gst_sales_items_table",
    indices = [
        Index(value = ["gst_invoice_id"]),
        Index(value = ["product_id"])
    ]
)
data class GstSalesInvoiceItem(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "gst_invoice_id")
    val gstInvoiceId: Int,

    @ColumnInfo(name = "product_id")
    val productId: Int,

    @ColumnInfo(name = "product_name")
    val productName: String,

    @ColumnInfo(name = "variant_name")
    val variantName: String? = null,

    @ColumnInfo(name = "hsn_code")
    val hsnCode: String = "",

    val quantity: Double,

    @ColumnInfo(name = "selling_price")
    val sellingPrice: Double,

    @ColumnInfo(name = "taxable_amount")
    val taxableAmount: Double,

    @ColumnInfo(name = "sales_cgst_percentage")
    val salesCgstPercentage: Double = 0.0,

    @ColumnInfo(name = "sales_sgst_percentage")
    val salesSgstPercentage: Double = 0.0,

    @ColumnInfo(name = "sales_igst_percentage")
    val salesIgstPercentage: Double = 0.0,

    @ColumnInfo(name = "cgst_amount")
    val cgstAmount: Double = 0.0,

    @ColumnInfo(name = "sgst_amount")
    val sgstAmount: Double = 0.0,

    @ColumnInfo(name = "igst_amount")
    val igstAmount: Double = 0.0,

    @ColumnInfo(name = "net_value")
    val netValue: Double = 0.0
)
