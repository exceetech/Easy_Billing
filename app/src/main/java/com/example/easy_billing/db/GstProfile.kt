package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local Room entity mirroring server's `store_gst_profile` table.
 *
 * Singleton row (id = 1) — there is only ever one GST profile per
 * shop installation. Tax percentages are deliberately *not* stored
 * here: the new model keeps CGST/SGST/IGST on the [Product] row
 * (i.e. `shop_product`).
 *
 * Sync semantics:
 *  - sync_status = "pending"   →  written locally, not yet pushed
 *  - sync_status = "synced"    →  in agreement with backend
 *  - sync_status = "failed"    →  push attempted but server rejected
 *
 * Conflict resolution = latest `updated_at` wins.
 */
@Entity(tableName = "gst_profile")
data class GstProfile(

    @PrimaryKey
    val id: Int = 1,

    @ColumnInfo(name = "shop_id")
    val shopId: String = "",

    val gstin: String,

    @ColumnInfo(name = "legal_name")
    val legalName: String = "",

    @ColumnInfo(name = "trade_name")
    val tradeName: String = "",

    @ColumnInfo(name = "gst_scheme")
    val gstScheme: String = "",                // REGULAR / COMPOSITION

    @ColumnInfo(name = "registration_type")
    val registrationType: String = "",         // Regular / Composition / Casual / etc.

    @ColumnInfo(name = "state_code")
    val stateCode: String = "",                // 2-digit, e.g. "29" for Karnataka

    @ColumnInfo(name = "address")
    val address: String = "",

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending",        // pending / synced / failed

    @ColumnInfo(name = "device_id")
    val deviceId: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
