package com.example.easy_billing.util

import android.content.Context
import com.example.easy_billing.db.AppDatabase

/**
 * Predefined product-category vocabulary plus helpers to merge in the
 * shop's custom categories for the dropdown.
 *
 * Categories are stored on the product as a plain string, so this list
 * is purely the suggestion set shown in the AutoCompleteTextView. Owners
 * can still type any custom value; new values are remembered via
 * [com.example.easy_billing.db.ProductCategory].
 */
object ProductCategories {

    const val UNCATEGORIZED = "Uncategorized"

    /** Broad retail vocabulary covering kirana, pharmacy, electronics, apparel, etc. */
    val PREDEFINED: List<String> = listOf(
        "Grocery & Staples",
        "Snacks & Namkeen",
        "Beverages",
        "Dairy & Eggs",
        "Bakery",
        "Frozen Foods",
        "Fruits & Vegetables",
        "Meat & Seafood",
        "Personal Care",
        "Cosmetics & Beauty",
        "Health & Wellness",
        "Medicines & Pharmacy",
        "Baby Care",
        "Household & Cleaning",
        "Kitchenware",
        "Home & Furnishing",
        "Stationery & Office",
        "Books",
        "Electronics",
        "Mobile & Accessories",
        "Computer & Peripherals",
        "Appliances",
        "Hardware & Tools",
        "Electrical",
        "Plumbing",
        "Paint",
        "Automotive",
        "Clothing & Apparel",
        "Footwear",
        "Bags & Luggage",
        "Jewellery & Accessories",
        "Toys & Games",
        "Sports & Fitness",
        "Pet Supplies",
        "Garden & Outdoor",
        "Tobacco & Pan",
        "Liquor",
        "Services",
        "Others"
    )

    /**
     * Returns the full dropdown list = predefined ∪ shop custom categories,
     * de-duplicated (case-insensitive), sorted, with "Others"/"Uncategorized"
     * pinned to the end.
     */
    suspend fun dropdownFor(context: Context, shopId: String): List<String> {
        val custom = try {
            AppDatabase.getDatabase(context).productCategoryDao()
                .getAllForShop(shopId).map { it.name }
        } catch (e: Exception) {
            emptyList()
        }

        val pinned = setOf("Others", UNCATEGORIZED)
        val merged = LinkedHashMap<String, String>() // lowercased key -> display
        (PREDEFINED + custom).forEach { raw ->
            val name = raw.trim()
            if (name.isNotEmpty()) merged.putIfAbsent(name.lowercase(), name)
        }

        val body = merged.values
            .filter { it !in pinned }
            .sortedBy { it.lowercase() }

        return body + listOf("Others", UNCATEGORIZED)
    }
}
