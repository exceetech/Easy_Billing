package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A supplier this shop buys from (`supplier_table`).
 *
 * **This table is a convenience index for autofill — not the source of
 * truth for any purchase.** `purchase_table` keeps its own denormalised
 * `supplierName` / `supplierGstin` / `state`, because a GST invoice is a
 * legal record of who you bought from *at that moment*. If a supplier
 * later changes name or re-registers, historical invoices must not move
 * with them, or already-filed GSTR-2 data stops matching.
 *
 * ### Identity
 * [gstin] is the identity, not [name]. GSTIN is government-issued and
 * globally unique, and its first two characters *are* the state code
 * (see `GstEngine.getStateCode`) — so [state] is derived, not independent.
 *
 * That makes the name-collision question mostly moot:
 *  - same name, different GSTIN (branches, e.g. "Raj Traders" in TN and
 *    KL) → two legitimate rows, told apart in the picker by GSTIN + state
 *  - same GSTIN, different spelling → one row; the newest spelling wins
 *
 * Unregistered suppliers have no GSTIN. They fall back to being keyed by
 * lowercased name, and [state] must then be stored explicitly since there
 * is no GSTIN to derive it from.
 *
 * Uniqueness: `(shopId, gstin)` is a unique index. SQLite treats NULLs as
 * distinct, so registered suppliers get one row each while any number of
 * unregistered (NULL-GSTIN) rows coexist. The unregistered "one row per
 * name" rule can't be a unique index — two *registered* suppliers are
 * allowed to share a name — so `SupplierRepository.remember` enforces it
 * by looking up [nameKey] before inserting.
 */
@Entity(
    tableName = "supplier_table",
    indices = [
        Index(value = ["shopId", "gstin"], unique = true),
        Index(value = ["shopId", "nameKey"]),
        Index(value = ["lastUsedAt"])
    ]
)
data class Supplier(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** Server-side id once synced; null while local-only. */
    val serverId: Int? = null,

    /**
     * 15-char GSTIN, uppercased. Null for unregistered suppliers.
     * Unique per shop when present.
     */
    val gstin: String? = null,

    /** Display name, as last typed by the user. A label, not a key. */
    val name: String,

    /**
     * `name.trim().lowercase()`. Only used as the fallback unique key for
     * suppliers with no GSTIN — keep it in sync whenever [name] changes.
     */
    @ColumnInfo(name = "nameKey")
    val nameKey: String,

    /** Full state name (matches `GstEngine.INDIA_STATES` values). */
    val state: String,

    /** Epoch millis of the last purchase recorded against this supplier. */
    val lastUsedAt: Long = System.currentTimeMillis(),

    val isSynced: Boolean = false,
    val isActive: Boolean = true,

    val shopId: Int
) {
    companion object {
        /** The fallback key for a supplier with no GSTIN. */
        fun keyOf(name: String): String = name.trim().lowercase()
    }
}
