package com.example.easy_billing.network

/**
 * Backend GST verification helpers.
 *
 * These cover three "is this a known thing globally?" lookups
 * exposed by the shop backend:
 *   • [HsnVerificationResponse]      — `GET /products/verify-hsn/{hsn}`
 *   • [VariantListResponse]          — `GET /products/{name}/variants`
 *   • [ProductNameVerifyResponse]    — `GET /products/verify-name`
 *
 * Sample payloads (server contract):
 *
 * ```json
 * // verify-hsn/0902
 * {
 *   "valid": true,
 *   "hsn": "0902",
 *   "description": "Tea, whether or not flavoured"
 * }
 *
 * // products/Cotton%20T-Shirt/variants
 * {
 *   "product_name": "Cotton T-Shirt",
 *   "variants": ["Small", "Medium", "Large", "XL"]
 * }
 * ```
 */
data class HsnVerificationResponse(
    val valid: Boolean,
    val hsn: String,
    val description: String? = null,
    val message: String? = null
)

data class VariantListResponse(
    val product_name: String,
    val variants: List<String> = emptyList()
)

data class ProductNameVerifyResponse(
    val valid: Boolean,
    val name: String,
    val matched_global_id: Int? = null,
    val message: String? = null
)
