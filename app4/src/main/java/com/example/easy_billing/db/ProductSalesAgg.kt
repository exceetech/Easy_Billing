package com.example.easy_billing.db

/**
 * Per-product sales aggregate used by the Dashboard sort options.
 *
 * Computed once in a single GROUP BY query when the grid loads, so that
 * sorting by units-sold / revenue / profit is a pure in-memory reorder
 * (no DB work per click).
 */
data class ProductSalesAgg(
    val productId: Int,
    val qty: Double,
    val revenue: Double,
    val profit: Double
)
