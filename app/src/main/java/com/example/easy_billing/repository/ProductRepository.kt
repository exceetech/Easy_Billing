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

    /**
     * Assets — products created for record-keeping only (purchase lines
     * with ITC eligibility "Capital goods" / "Input services",
     * isSellable = false). Backs [com.example.easy_billing.AssetsActivity].
     */
    suspend fun getAssetsForCurrentShop(): List<Product> {
        val validIds = getValidShopIds()
        return validIds.flatMap { productDao.getAssetsForShop(it) }
            .distinctBy { it.id }
    }

    suspend fun getById(id: Int): Product? = productDao.getById(id)

    /**
     * @param isSellable which bucket to match against — a sellable-catalog
     * lookup (`true`) must never return an asset row of the same
     * name+variant, and vice versa. See [ProductDao.getByNameAndVariant].
     */
    suspend fun getByNameAndVariant(name: String, variant: String?, isSellable: Boolean = true): Product? =
        productDao.getByNameAndVariant(capitalize(name), variant?.let(::capitalize), getValidShopIds(), isSellable)

    suspend fun getInactiveByNameAndVariant(name: String, variant: String?): Product? =
        productDao.getInactiveByNameAndVariant(capitalize(name), variant?.let(::capitalize), getValidShopIds())

    /**
     * A product that differs only in capitalisation. Detection only — the
     * unique (shop_id, name, variant) index is case-*sensitive*, so this is
     * how a caller spots a clash it would otherwise only learn about from a
     * constraint error. Never use the result to decide what to update.
     *
     * @param isSellable same sellable/asset scoping as [getByNameAndVariant].
     */
    suspend fun findConflictIgnoringCase(name: String, variant: String?, isSellable: Boolean = true): Product? =
        productDao.findConflictIgnoringCase(capitalize(name), variant?.let(::capitalize), getValidShopIds(), isSellable)

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
     * Matching is [Product.isSellable]-scoped: a sellable "Fridge" and a
     * non-sellable/asset "Fridge" (see the Assets feature — Capital goods /
     * Input services purchases) are separate rows by design, even though
     * they share the same (shop_id, name, variant) key modulo that flag.
     * `product.isSellable` (as set by the caller — PurchaseRepository.doSave
     * derives it from the line's ITC eligibility) decides which bucket this
     * upsert can land in; it will never reuse or overwrite a row in the
     * other bucket. If no row exists in the intended bucket, a new one is
     * created even if a same-named row exists in the other bucket.
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
        var existing = productDao.getByNameAndVariant(normalized.name, normalized.variant, validShopIds, normalized.isSellable)

        // [capitalize] only fixes the FIRST letter of each word and leaves the
        // rest of the casing exactly as typed — so "Potato Chips" and "Potato
        // CHIPS" (or any other mid-word case difference) are NOT the same
        // string after normalization and the exact lookup above misses the
        // match. Without this fallback, restocking an existing product with
        // even a slightly different capitalization silently created a second
        // product + a second inventory row instead of adding to the same
        // one — two tiles for "the same" product, each with its own
        // separate stock and cost instead of one row with the correct
        // combined stock and weighted-average cost. AddProductActivity
        // already guards against exactly this with findConflictIgnoringCase;
        // this mirrors that same protection here, since this upsert() is the
        // path Purchases (and everything else) actually use to decide
        // whether a product already exists.
        if (existing == null) {
            existing = productDao.findConflictIgnoringCase(normalized.name, normalized.variant, validShopIds, normalized.isSellable)
        }

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
     * untouched. Was used by the retired AddProductsActivity price dialog
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
        category: String? = null,
        isTaxInclusive: Boolean = false
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
            supplyClassification = supplyClassification,
            isTaxInclusive = isTaxInclusive
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
        //
        // Report 5 fix: this used to be truly fire-and-forget — a failed
        // call here meant the backend kept stale price/tax data forever,
        // with no retry and no error surfaced to the user (the local write
        // above already succeeded, so the screen just said "saved"). The
        // local row was already stamped pending_field_sync = 1 by
        // updateSalesFields(); we only clear it here on confirmed success,
        // so a failure leaves it set and SyncManager.syncProductFieldEdits()
        // picks it up and retries on the next background sync pass.
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
                    is_purchased     = product.isPurchased,
                    is_tax_inclusive = isTaxInclusive,
                    is_sellable      = product.isSellable
                )
            )
        }.onSuccess {
            productDao.markFieldSynced(productId)
        } // onFailure: leave pending_field_sync = 1, SyncManager retries it
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

    /**
     * Inserts a new local category row for [shopId] if `category` isn't a
     * predefined/uncategorized name and doesn't already exist. Called after
     * saving or restoring a product so a genuinely new category shows up in
     * future category pickers. Lifted 1:1 from AddProductActivity's private
     * helper of the same name/behavior.
     */
    suspend fun rememberCategoryIfNew(category: String, shopId: String) {
        val name = category.trim()
        if (name.isEmpty()) return
        if (com.example.easy_billing.util.ProductCategories.PREDEFINED.any { it.equals(name, true) }) return
        if (name.equals(com.example.easy_billing.util.ProductCategories.UNCATEGORIZED, true)) return
        if (db.productCategoryDao().getByName(name, shopId) == null) {
            db.productCategoryDao().insertIgnore(
                com.example.easy_billing.db.ProductCategory(shopId = shopId, name = name)
            )
        }
    }

    /**
     * Mirrors [product]'s current fields to the backend. Fire-and-forget —
     * the local row is authoritative, so a network failure must not surface
     * as an error to the caller. Lifted 1:1 from AddProductActivity's
     * pushRestoredProduct(); see that function's original doc comment for
     * why this exists as a separate push rather than relying on
     * SyncManager alone (short version: activate() only flips isActive,
     * the price/GST/HSN the user just typed needs an explicit push same as
     * every other edit path in the app, e.g. updateSalesFieldsOnly above).
     */
    suspend fun pushProductUpdate(product: Product) {
        val serverId = product.serverId ?: return
        val token = ContextHolder.app
            ?.getSharedPreferences("auth", Context.MODE_PRIVATE)
            ?.getString("TOKEN", null) ?: return
        runCatching {
            RetrofitClient.api.updateShopProduct(
                token = "Bearer $token",
                serverId = serverId,
                request = AddProductRequest(
                    name = product.name,
                    variant_name = product.variant?.ifBlank { null },
                    unit = product.unit ?: "piece",
                    price = product.price,
                    track_inventory = product.trackInventory,
                    initial_stock = null,   // stock is never touched by a restore
                    cost_price = null,
                    hsn_code = product.hsnCode?.takeIf { it.isNotBlank() },
                    default_gst_rate = product.defaultGstRate,
                    cgst_percentage = product.cgstPercentage,
                    sgst_percentage = product.sgstPercentage,
                    igst_percentage = product.igstPercentage,
                    official_uqc = product.officialUqc,
                    hsn_description = product.hsnDescription,
                    cess_rate = product.cessRate,
                    supply_classification = product.supplyClassification,
                    category = product.category,
                    is_purchased = product.isPurchased,
                    is_tax_inclusive = product.isTaxInclusive,
                    is_sellable = product.isSellable
                )
            )
        }
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
