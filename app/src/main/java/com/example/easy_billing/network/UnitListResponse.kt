package com.example.easy_billing.network

/**
 * Response shape for `GET /units` — the shop-scoped list of unit
 * symbols/words that the backend recognises (extends the standard
 * "piece / kg / litre / gram / ml" set with whatever the backend
 * has registered for this shop).
 *
 * If the endpoint isn't implemented or returns an empty list, the
 * client falls back to its built-in defaults — see
 * [com.example.easy_billing.PurchaseActivity.showLineDialog].
 *
 * Sample payload:
 *
 * ```json
 * {
 *   "units": ["piece", "kg", "litre", "gram", "ml", "dozen", "box"]
 * }
 * ```
 */
data class UnitListResponse(
    val units: List<String> = emptyList()
)
