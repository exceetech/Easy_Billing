package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Supplier
import com.example.easy_billing.util.GstEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and upserts the supplier autofill index.
 *
 * Nothing here is on the critical path of saving a purchase — a failure to
 * remember a supplier must never fail the purchase itself, so [remember]
 * swallows its own errors.
 */
object SupplierRepository {

    private const val GSTIN_LENGTH = 15

    /**
     * The signed-in shop, or null when there isn't one.
     *
     * Never falls back to a real id. A default of 1 meant a half-initialised
     * session filed suppliers into shop 1's books instead of refusing —
     * silently, since nothing here reports errors.
     */
    private fun shopIdOrNull(context: Context): Int? =
        context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getInt("SHOP_ID", -1)
            .takeIf { it > 0 }

    suspend fun all(context: Context): List<Supplier> = withContext(Dispatchers.IO) {
        val shop = shopIdOrNull(context) ?: return@withContext emptyList()
        AppDatabase.getDatabase(context).supplierDao().getAll(shop)
    }

    suspend fun byGstin(context: Context, gstin: String): Supplier? = withContext(Dispatchers.IO) {
        val shop = shopIdOrNull(context) ?: return@withContext null
        AppDatabase.getDatabase(context).supplierDao()
            .getByGstin(gstin.trim().uppercase(), shop)
    }

    /**
     * Every supplier stored under [name].
     *
     * More than one is normal (same trade name, different GSTIN / branch),
     * which is exactly why callers must not autofill from a name unless
     * this returns a single row.
     */
    suspend fun byName(context: Context, name: String): List<Supplier> = withContext(Dispatchers.IO) {
        val shop = shopIdOrNull(context) ?: return@withContext emptyList()
        AppDatabase.getDatabase(context).supplierDao()
            .getByName(Supplier.keyOf(name), shop)
    }

    /** Why a [create] call was refused, so the caller can say which field. */
    sealed class CreateResult {
        data class Ok(val supplier: Supplier) : CreateResult()
        object BlankName : CreateResult()
        object BadGstin : CreateResult()
        object NoState : CreateResult()
        data class Duplicate(val existing: Supplier) : CreateResult()

        /** No shop is signed in — nothing can be filed against one. */
        object NoShop : CreateResult()
    }

    /**
     * Creates a supplier from the "New supplier" dialog.
     *
     * Rejects rather than silently merging when the GSTIN already exists —
     * the user asked to add something new, so being told "you already have
     * this one" is more useful than quietly selecting a different row.
     */
    suspend fun create(
        context: Context,
        name: String,
        gstin: String?,
        state: String
    ): CreateResult = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return@withContext CreateResult.BlankName

        val rawGstin = gstin?.trim()?.uppercase().orEmpty()
        if (rawGstin.isNotEmpty() && !GstEngine.isValidGstin(rawGstin))
            return@withContext CreateResult.BadGstin
        val cleanGstin = rawGstin.takeIf { it.isNotEmpty() }

        // GSTIN wins over the picked state — it's the registered one.
        val resolvedState = cleanGstin
            ?.let { GstEngine.INDIA_STATES[GstEngine.getStateCode(it)] }
            ?: state.trim()
        if (resolvedState.isBlank()) return@withContext CreateResult.NoState

        val dao = AppDatabase.getDatabase(context).supplierDao()
        val shop = shopIdOrNull(context) ?: return@withContext CreateResult.NoShop

        val clash = if (cleanGstin != null) {
            dao.getByGstinAny(cleanGstin, shop)
        } else {
            dao.getUnregisteredByName(Supplier.keyOf(cleanName), shop).firstOrNull()
        }
        if (clash != null) return@withContext CreateResult.Duplicate(clash)

        val row = Supplier(
            gstin = cleanGstin,
            name = cleanName,
            nameKey = Supplier.keyOf(cleanName),
            state = resolvedState,
            lastUsedAt = System.currentTimeMillis(),
            shopId = shop
        )
        val id = dao.insert(row).toInt()
        CreateResult.Ok(row.copy(id = id))
    }

    /**
     * Records (or refreshes) a supplier after a purchase is saved.
     *
     * Matching order:
     *  1. by GSTIN when one was entered — the real identity, so a renamed
     *     or re-spelled supplier updates in place instead of duplicating
     *  2. by name only when there is no GSTIN and exactly one nameless-GSTIN
     *     row matches — anything ambiguous inserts a new row rather than
     *     guessing and corrupting an existing one
     *
     * Never throws.
     */
    suspend fun remember(
        context: Context,
        name: String,
        gstin: String?,
        state: String
    ) = withContext(Dispatchers.IO) {
        try {
            val cleanName = name.trim()
            if (cleanName.isEmpty()) return@withContext

            // Only a complete GSTIN is an identity. A half-typed one ("33AA")
            // would take the unique (shopId, gstin) slot and never match the
            // real thing again, so it's stored as unregistered instead.
            val cleanGstin = gstin?.trim()?.uppercase()?.takeIf { it.length == GSTIN_LENGTH }
            // GSTIN carries the state in its first two characters, so prefer
            // that over whatever was typed in the state box.
            val resolvedState = cleanGstin
                ?.let { GstEngine.INDIA_STATES[GstEngine.getStateCode(it)] }
                ?: state.trim()

            val dao = AppDatabase.getDatabase(context).supplierDao()
            val shop = shopIdOrNull(context) ?: return@withContext
            val now = System.currentTimeMillis()

            val existing = when {
                cleanGstin != null -> dao.getByGstin(cleanGstin, shop)
                else -> dao.getByName(Supplier.keyOf(cleanName), shop)
                    .filter { it.gstin == null }
                    .singleOrNull()
            }

            if (existing != null) {
                val newState = resolvedState.ifBlank { existing.state }
                // updatedAt moves only when a detail actually changed. A
                // repeat purchase from an unchanged supplier bumps usage, not
                // modification — otherwise every purchase would outrank a
                // genuine rename made on another device.
                val detailsChanged =
                    existing.name != cleanName || existing.state != newState

                dao.update(
                    existing.copy(
                        name = cleanName,
                        nameKey = Supplier.keyOf(cleanName),
                        state = newState,
                        lastUsedAt = now,
                        updatedAt = if (detailsChanged) now else existing.updatedAt,
                        isSynced = false
                    )
                )
            } else {
                dao.insert(
                    Supplier(
                        gstin = cleanGstin,
                        name = cleanName,
                        nameKey = Supplier.keyOf(cleanName),
                        state = resolvedState,
                        lastUsedAt = now,
                        shopId = shop
                    )
                )
            }
        } catch (e: Exception) {
            // Autofill bookkeeping must never break a saved purchase.
            e.printStackTrace()
        }
    }
}
