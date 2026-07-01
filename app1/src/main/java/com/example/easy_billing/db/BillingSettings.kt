package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Billing-side configuration: just the printer layout now.
 *
 * `defaultGst` is kept (legacy column) but is no longer surfaced
 * in the BillingSettings UI; tax percentages live on
 * [Product] (`shop_product`).
 */
@Entity(tableName = "billing_settings")
data class BillingSettings(

    @PrimaryKey val id: Int = 1,

    val defaultGst: Float,
    val printerLayout: String
)
