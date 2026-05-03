package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Product
import com.example.easy_billing.db.ProductDao

/**
 * Repository for the local `shop_product` (a.k.a. `products`) table.
 *
 * Owns:
 *   • Upsert (insert-or-update with first-letter capitalization).
 *   • Auto-fill lookups by name / HSN.
 *   • Catalogue helpers used by Add-Product + Purchase screens.
 *   • Shop-scoped reads — every query that backs a UI tile is
 *     filtered by the currently authenticated shop's id.
 *
 * Tax rates (CGST/SGST/IGST) are *product-level* per the current
 * spec — they are stored on this row, not on store/billing config.
 * Global products do NOT carry tax; only this local table does.
 */
class ProductRepository private constructor(
    private val productDao: ProductDao,
    private val db: AppDatabase
) {

    /* ------------------------------------------------------------------
     *  Reads
     * ------------------------------------------------------------------ */

    suspend fun getAllActive(): List<Product> = productDao.getAll()

    /** Active products bound to the currently authenticated shop. */
    suspend fun getAllForCurrentShop(): List<Product> =
        productDao.getAllForShop(currentShopId())

    suspend fun getById(id: Int): Product? = productDao.getById(id)

    suspend fun getByNameAndVariant(name: String, variant: String?): Product? =
        productDao.getByNameAndVariant(capitalize(name), variant?.let(::capitalize))

    /**
     * Auto-fill: when the user enters a product name *or* an HSN
     * code on Add-Product / Purchase, look up the most recent
     * matching shop_product row so we can pre-populate HSN +
     * CGST/SGST/IGST.
     */
    suspend fun autoFillFromHistory(
        name: String? = null,
        hsn: String? = null
    ): Product? {
        if (!name.isNullOrBlank()) {
            productDao.findByName(capitalize(name))?.let { return it }
        }
        if (!hsn.isNullOrBlank()) {
            productDao.findByHsn(hsn.trim())?.let { return it }
        }
        return null
    }

    suspend fun distinctNames(): List<String> = productDao.getDistinctNames()
    suspend fun distinctVariants(): List<String> = productDao.getDistinctVariants()

    /* ------------------------------------------------------------------
     *  Writes
     * ------------------------------------------------------------------ */

    /**
     * Insert a brand-new product, or update an existing match by
     * (name, variant). Names + variants are capitalised before
     * persisting. The `isPurchased` flag is *latched* — once a
     * product has been purchased it stays marked, even if a later
     * manual upsert tries to clear it.
     *
     * @return the row id (existing or newly inserted).
     */
    suspend fun upsert(product: Product): Int {
        val normalized = product.copy(
            name = capitalize(product.name),
            variant = product.variant?.let(::capitalize),
            shopId = product.shopId.ifBlank { currentShopId() }
        )
        val existing = productDao.getByNameAndVariant(normalized.name, normalized.variant)
        return if (existing == null) {
            productDao.insert(normalized).toInt()
        } else {
            productDao.update(
                normalized.copy(
                    id = existing.id,
                    serverId = normalized.serverId ?: existing.serverId,
                    isActive = true,
                    isPurchased = existing.isPurchased || normalized.isPurchased,
                    shopId = existing.shopId.ifBlank { normalized.shopId }
                )
            )
            existing.id
        }
    }

    /**
     * Restricted update for purchased products — only price + GST
     * (and HSN) may change. Stock and inventory flags are left
     * untouched. Used by [com.example.easy_billing.AddProductsActivity.showUpdatePriceDialog]
     * when the row's `isPurchased == true`.
     */
    suspend fun updateSalesFieldsOnly(
        productId: Int,
        price: Double,
        cgst: Double,
        sgst: Double,
        igst: Double,
        hsn: String?
    ) {
        val combined = (cgst + sgst).takeIf { it > 0 } ?: igst
        productDao.updateSalesFields(
            id = productId,
            price = price,
            cgst = cgst,
            sgst = sgst,
            igst = igst,
            defaultGst = combined,
            hsnCode = hsn?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Apply *sales-side* tax to a product (used by the Purchase
     * flow when the user has set sales rates on a line item).
     */
    suspend fun applySalesTax(
        productId: Int,
        cgst: Double,
        sgst: Double,
        igst: Double,
        hsn: String? = null
    ) {
        val current = productDao.getById(productId) ?: return
        productDao.update(
            current.copy(
                cgstPercentage = cgst,
                sgstPercentage = sgst,
                igstPercentage = igst,
                defaultGstRate = (cgst + sgst).takeIf { it > 0 } ?: igst,
                hsnCode = hsn?.trim()?.takeIf { it.isNotBlank() } ?: current.hsnCode
            )
        )
    }

    /* ------------------------------------------------------------------
     *  Shop-scoping
     * ------------------------------------------------------------------ */

    /**
     * Best identifier we have for "which shop is logged in":
     *   1. The `gstin` on `store_info`, if present.
     *   2. Otherwise the auth shop id from prefs.
     *   3. Otherwise the empty string (legacy installs).
     */
    suspend fun currentShopId(): String {
        val gstin = db.storeInfoDao().get()?.gstin?.takeIf { it.isNotBlank() }
        if (gstin != null) return gstin
        val ctx = ContextHolder.app ?: return ""
        val prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
        
        return try {
            // Try reading as String first
            prefs.getString("SHOP_ID", null) ?: prefs.getInt("SHOP_ID", 0).toString()
        } catch (e: ClassCastException) {
            // If it's stored as an Integer, read as Int and convert to String
            prefs.getInt("SHOP_ID", 0).toString()
        }
    }

    /* ------------------------------------------------------------------
     *  Helpers
     * ------------------------------------------------------------------ */

    /**
     * "first letter capital" — capitalize the first alphabetic
     * character of every word, leave the rest untouched. Used for
     * both product names and variants.
     */
    private fun capitalize(value: String?): String =
        value?.trim()?.split(Regex("\\s+"))?.joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word.first().uppercaseChar() + word.drop(1)
        }.orEmpty()

    /** Tiny holder so the repo can still resolve a Context for prefs. */
    private object ContextHolder {
        var app: Context? = null
    }

    companion object {
        @Volatile private var INSTANCE: ProductRepository? = null

        fun get(context: Context): ProductRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    ContextHolder.app = context.applicationContext
                    ProductRepository(
                        productDao = AppDatabase.getDatabase(context).productDao(),
                        db = AppDatabase.getDatabase(context)
                    ).also { INSTANCE = it }
                }
            }
        }
    }
}
