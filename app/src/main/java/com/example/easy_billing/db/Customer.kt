package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Customer master, keyed by mobile number per shop.
 *
 * Additive convenience layer for the invoice screen: entering a phone
 * looks a customer up here and auto-fills B2B/B2C details. It does NOT
 * replace the per-invoice customer snapshot still written to
 * [GstSalesInvoice] (which remains the source of truth for printing /
 * GST / history), so this change is regression-free.
 *
 * Intentionally separate from [CreditAccount] (credit balance
 * semantics). When a customer also uses credit, [creditAccountId] links
 * to the existing credit account without merging the two.
 *
 * Sync: unique (shopId, phone) locally and on the server; the server
 * upserts by (shop_id, phone) so two devices that create the same phone
 * offline converge to one server id. Field conflicts resolve by latest
 * [updatedAt].
 */
@Entity(
    tableName = "customers",
    indices = [
        // One record per (shop, phone, type) — a customer may have BOTH a
        // B2C and a B2B entry under the same number, kept separate.
        Index(value = ["shop_id", "phone", "customer_type"], unique = true)
    ]
)
data class Customer(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val serverId: Int? = null,

    @ColumnInfo(name = "shop_id")
    val shopId: Int,

    val phone: String,

    val name: String = "",

    /** "B2B" or "B2C". */
    @ColumnInfo(name = "customer_type")
    val customerType: String = "B2C",

    @ColumnInfo(name = "business_name")
    val businessName: String? = null,

    val gstin: String? = null,

    val state: String? = null,

    @ColumnInfo(name = "state_code")
    val stateCode: String? = null,

    @ColumnInfo(name = "credit_account_id")
    val creditAccountId: Int? = null,

    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
