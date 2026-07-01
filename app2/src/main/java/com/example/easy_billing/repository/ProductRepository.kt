package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Product
import com.example.easy_billing.db.ProductDao
import com.example.easy_billing.network.AddProductRequest
import com.example.easy_billing.network.RetrofitClient

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

    /**
     * Active products bound to the currently authenticated shop.
     *
     * The `shopId` column has been set with different formats over
     * time — GSTIN ("29ABCDE..."), numeric SHOP_ID string ("42"),
     * or empty string (legacy rows). We collect every valid ID for
     * the current session and match all of them so a format change
     * (e.g. GSTIN synced after products were already inserted with
     * the numeric ID) never makes tiles disappear.
     */
    suspend fun getValidShopIds(): List<String> {
        val validIds = buildSet {
            add("")                                           // legacy rows (shopId not set)
            db.storeInfoDao().get()?.gstin
                ?.takeIf { it.isNotBlank() }?.let { add(it) } // GSTIN format
            val ctx = ContextHolder.app ?: return@buildSet
            val prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
            val numericId = try {
                prefs.getString("SHOP_ID", null)
                    ?: prefs.getInt("SHOP_ID", 0).toString()
            } catch (e: ClassCastException) {
                prefs.getInt("SHOP_ID", 0).toString()
            }
            if (numericId.isNotBlank() && numericId != "0") add(numericId)
        }
        return validIds.toList()
    }

    suspend fun getAllForCurrentShop(): List<Product> {
        val validIds = getValidShopIds()
        return productDao.getAll().filter { it.shopId in validIds }
    }

    suspend fun getById(id: Int): Product? = productDao.getById(id)

    suspend fun getByNameAndVariant(name: String, variant: String?): Product? =
        productDao.getByNameAndVariant(capitalize(name), variant?.let(::capitalize), getValidShopIds())
        
    suspend fun getInactiveByNameAndVariant(name: String, variant: String?): Product? =
        productDao.getInactiveByNameAndVariant(capitalize(name), variant?.let(::capitalize), getValidShopIds())

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
        val shopIds = getValidShopIds()
        if (!name.isNullOrBlank()) {
            productDao.findByName(capitalize(name), shopIds)?.let { return it }
        }
        if (!hsn.isNullOrBlank()) {
            productDao.findByHsn(hsn.trim(), shopIds)?.let { return it }
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
        val validShopIds = getValidShopIds()
        val existing = productDao.getByNameAndVariant(normalized.name, normalized.variant, validShopIds)
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
        hsn: String?,
        officialUqc: String? = null,
        hsnDescription: String? = null,
        cessRate: Double = 0.0,
        supplyClassification: String = "TAXABLE",
        category: String? = null
    ) {
        val combined = (cgst + sgst).takeIf { it > 0 } ?: igst
        productDao.updateSalesFields(
            id = productId,
            price = price,
            cgst = cgst,
            sgst = sgst,
            igst = igst,
            defaultGst = combined,
            hsnCode = hsn?.takeIf { it.isNotBlank() },
            officialUqc = officialUqc,
            hsnDescription = hsnDescription,
            cessRate = cessRate,
            supplyClassification = supplyClassification
        )

        // Persist an edited category locally (kept separate from
        // updateSalesFields so its query stays untouched). Null = leave
        // the existing value unchanged.
        if (category != null) {
            productDao.getById(productId)?.let { p ->
                if (p.category != category) productDao.update(p.copy(category = category))
            }
        }

        // ── Inline backend push ──────────────────────────────────────
        // If this product is already on the server, push the updated
        // fields immediately so the backend table stays in sync.
        val product = productDao.getById(productId) ?: return
        val serverId = product.serverId ?: return
        val token = ContextHolder.app
            ?.getSharedPreferences("auth", Context.MODE_PRIVATE)
            ?.getString("TOKEN", null) ?: return
        runCatching {
            RetrofitClient.api.updateShopProduct(
                token  = "Bearer $token",
                serverId = serverId,
                request = AddProductRequest(
                    name             = product.name,
                    variant_name     = product.variant?.ifBlank { null },
                    unit             = product.unit ?: "piece",
                    price            = price,
                    track_inventory  = product.trackInventory,
                    initial_stock    = null,
                    cost_price       = null,
                    hsn_code         = hsn?.takeIf { it.isNotBlank() },
                    default_gst_rate = combined,
                    cgst_percentage  = cgst,
                    sgst_percentage  = sgst,
                    igst_percentage  = igst,
                    official_uqc     = officialUqc,
                    hsn_description  = hsnDescription,
                    cess_rate        = cessRate,
                    supply_classification = supplyClassification,
                    category         = product.category,
                    is_purchased     = product.isPurchased
                )
            )
        } // fire-and-forget — local write is authoritative; network failure is silent
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
     * Stable identifier for "which shop is logged in".
     *
     * We use the numeric SHOP_ID from auth prefs rather than the
     * GSTIN so that the shopId column in the products table is
     * always written with the same format. GSTIN can change (when
     * a shop registers or updates their GST number) while the
     * backend shop_id never does.
     *
     * [getAllForCurrentShop] also checks the GSTIN so that rows
     * written before this change (which used GSTIN) are still
     * returned correctly — there is no data migration required.
     */
    suspend fun currentShopId(): String {
        val ctx = ContextHolder.app ?: return ""
        val prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
        return try {
            prefs.getString("SHOP_ID", null) ?: prefs.getInt("SHOP_ID", 0).toString()
        } catch (e: ClassCastException) {
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
