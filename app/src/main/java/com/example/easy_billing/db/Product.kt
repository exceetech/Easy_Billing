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
        Index(value = ["shop_id"]),
        Index(value = ["shop_id", "name", "variant"], unique = true)
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

    /**
     * Unused. Added in v48 for an offline hide/restore queue that was
     * removed again; the column stays so databases that already ran
     * MIGRATION_47_48 keep matching the entity. Safe to drop in a future
     * migration alongside a schema bump.
     */
    val activeStateSynced: Boolean = true,
    val isTaxInclusive: Boolean = false,

    @ColumnInfo(name = "is_purchased")
    val isPurchased: Boolean = false,

    /**
     * Product category (e.g. "Grocery & Staples"). Stored as a plain
     * string so it always travels with the product through sync — no
     * foreign-key/ordering dependency. Empty = "Uncategorized".
     */
    val category: String = "",

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
    val igstPercentage: Double = 0.0,

    // ── GSTR-1 product master fields (v23) ───────────────────────────

    /**
     * Official GST Unit Quantity Code (e.g. "NOS", "KGS", "LTR").
     * User-supplied via AddProduct / EditProduct UI.
     * Falls back to UqcMapper.fromUnit(unit) if blank.
     */
    @ColumnInfo(name = "official_uqc")
    val officialUqc: String? = null,

    /**
     * Human-readable HSN description for GSTR-1 HSN summary.
     * Optional; falls back to product name if blank.
     */
    @ColumnInfo(name = "hsn_description")
    val hsnDescription: String? = null,

    /**
     * Cess rate (%) applicable on this product.
     * Default 0.  Must be >= 0.
     */
    @ColumnInfo(name = "cess_rate")
    val cessRate: Double = 0.0,

    /**
     * Supply Classification for GSTR-1.
     * Allowed: TAXABLE, NIL_RATED, EXEMPT, NON_GST
     * Default: TAXABLE
     */
    @ColumnInfo(name = "supply_classification")
    val supplyClassification: String = "TAXABLE",

    // ── Field-edit sync pulse (v55) ─────────────────────────────────────
    /**
     * True when this product's price/GST/HSN fields were edited locally
     * (via EditProductActivity) after it already had a [serverId], and the
     * backend hasn't confirmed receiving that edit yet.
     *
     * Report 5 fix: edits to an already-uploaded product used to be pushed
     * with a single fire-and-forget call (ProductRepository.
     * updateSalesFieldsOnly) with no retry and no local record of failure —
     * a flaky connection at save time meant the backend silently kept the
     * OLD price/tax data forever, with the user seeing "saved successfully"
     * regardless. This flag lets SyncManager find and retry any edit that
     * never landed. New products (serverId == null) are unaffected — those
     * already retry via the normal getUnsynced() path.
     */
    @ColumnInfo(name = "pending_field_sync", defaultValue = "0")
    val pendingFieldSync: Boolean = false,

    /**
     * True when this product was deactivated (removed) locally but the
     * backend hasn't confirmed the deactivation yet.
     *
     * Fix: removing a product used to call the backend deactivateProduct()
     * endpoint FIRST and only write the local soft-delete if that call
     * succeeded — the one write flow in the app that wasn't offline-first.
     * Deactivating while offline silently did nothing at all. Now the local
     * deactivate always happens immediately; this flag lets SyncManager
     * retry the backend call until it's confirmed.
     */
    @ColumnInfo(name = "pending_deactivate_sync", defaultValue = "0")
    val pendingDeactivateSync: Boolean = false

) : Serializable
