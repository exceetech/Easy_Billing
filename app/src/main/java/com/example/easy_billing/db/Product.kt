package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * Local product (`shop_product`) entity.
 *
 * After the GST refactor, tax percentages are **product-level** —
 * CGST/SGST/IGST live on this row, not on the store/billing config.
 *
 *   • [isPurchased]        — `true` when the row was created by a
 *     purchase invoice. Edit screens must restrict stock-mutating
 *     UI for these rows; only selling price and GST may change.
 *     Manual / "own product" rows have `isPurchased = false`.
 *
 *   • [shopId]             — bound at insert time to the currently
 *     authenticated shop (`StoreInfo.gstin` or auth shop id). Local
 *     filtering by this column protects against ghost rows when
 *     more than one shop has logged in on the same device.
 *
 *   • [hsnCode]            — HSN/SAC for this product (mandatory if
 *     the shop is GST-enabled).
 *   • [cgstPercentage]     — Sales-side tax fields. These are the
 *   • [sgstPercentage]       rates applied at sale time and are also
 *   • [igstPercentage]       what the auto-fill in AddProduct uses
 *                           when the user re-enters the same name
 *                           or HSN later.
 *   • [defaultGstRate]     — Legacy combined rate; left in place to
 *                           avoid breaking older billing logic that
 *                           reads it. New code should prefer the
 *                           three explicit percentages above.
 */
@Entity(
    tableName = "products",
    indices = [
        Index(value = ["name"]),
        Index(value = ["hsnCode"]),
        Index(value = ["shop_id"])
    ]
)
data class Product(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val name: String,
    val variant: String?,
    val unit: String?,
    val price: Double,

    val serverId: Int? = null,

    val trackInventory: Boolean,
    val isCustom: Boolean = false,
    val isActive: Boolean = true,

    @ColumnInfo(name = "is_purchased")
    val isPurchased: Boolean = false,

    @ColumnInfo(name = "shop_id")
    val shopId: String = "",

    // GST fields
    val hsnCode: String? = null,
    val defaultGstRate: Double = 0.0,

    @ColumnInfo(name = "cgst_percentage")
    val cgstPercentage: Double = 0.0,

    @ColumnInfo(name = "sgst_percentage")
    val sgstPercentage: Double = 0.0,

    @ColumnInfo(name = "igst_percentage")
    val igstPercentage: Double = 0.0

) : Serializable
