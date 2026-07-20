package com.example.easy_billing.util

import android.content.Context
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.VariantResponse

/**
 * Shared global-catalog autofill rules, used by both the Add Product
 * screen and the purchase line dialog so the two behave identically.
 *
 * This owns the *data* and the *decisions* only — each screen still
 * writes the values onto its own views, because the two layouts use
 * different widget types.
 *
 * The single source of catalog data is `variants-by-name`, which returns
 * the full statutory payload (HSN, description, UQC, GST split, cess and
 * unit) for every variant of a product in one call. Nothing here derives
 * a tax rate by guesswork — if the catalog doesn't know a value, the
 * field is left for the user to fill.
 */
object CatalogAutofill {

    /**
     * Fetches every catalog variant for [name], de-duplicated by variant
     * name (defensive against legacy duplicate rows).
     *
     * Returns `null` when the lookup could not be performed (signed out
     * or offline) so callers can retry later, and an **empty list** when
     * the catalog genuinely has nothing for this product. Callers must
     * never treat either case as a reason to substitute unrelated data.
     */
    suspend fun fetchVariants(context: Context, name: String): List<VariantResponse>? {
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return null
        val raw = try {
            RetrofitClient.api.getVariantsByName(token, name.trim())
        } catch (_: Exception) {
            return null
        }
        val seen = HashSet<String>()
        return raw.filter { seen.add(it.variant_name.trim().lowercase()) }
    }

    /** Variants that carry a real name (the user-choosable ones). */
    fun namedVariants(variants: List<VariantResponse>): List<VariantResponse> =
        variants.filter { it.variant_name.isNotBlank() }

    /**
     * The product-level row (blank variant name) that is safe to apply
     * from the product name alone.
     *
     * If the product has real named variants there is a genuine choice to
     * make, so nothing is applied until the user settles on one — filling
     * fields from the name alone would be guessing.
     */
    fun productLevelDefault(variants: List<VariantResponse>): VariantResponse? {
        if (namedVariants(variants).isNotEmpty()) return null
        return variants.firstOrNull { it.variant_name.isBlank() }
    }

    /** The catalog entry for an explicitly chosen variant name. */
    fun variantNamed(variants: List<VariantResponse>, chosen: String): VariantResponse? =
        variants.firstOrNull { it.variant_name.equals(chosen.trim(), ignoreCase = true) }
}
