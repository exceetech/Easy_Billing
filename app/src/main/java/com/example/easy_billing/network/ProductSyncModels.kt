package com.example.easy_billing.network

/**
 * Sync DTOs for the local `shop_product` table and the
 * "register globally" flow.
 *
 * Two endpoints feed off these:
 *
 *   • `POST /products/sync` — pushes any local rows that don't have
 *     a `serverId` yet (e.g. created while offline). Server returns
 *     a `local_id → server_id` map.
 *
 *   • `POST /products/global/register` — registers a product, its
 *     HSN, and its variant in the **global** catalogue. Always
 *     called alongside `addProductToShop` so the global table is
 *     guaranteed to receive the row even if the shop endpoint
 *     doesn't promote on its own.
 */
data class ShopProductSyncRequest(
    val products: List<ShopProductDto>
)

data class ShopProductDto(
    val local_id: Int,
    val name: String,
    val variant: String?,
    val unit: String?,
    val price: Double,
    val track_inventory: Boolean,
    val is_custom: Boolean,
    val is_active: Boolean,
    val hsn_code: String?,
    val cgst_percentage: Double,
    val sgst_percentage: Double,
    val igst_percentage: Double,
    val default_gst_rate: Double
)

data class ShopProductSyncResponse(
    val success_count: Int = 0,
    val product_id_map: Map<String, Int> = emptyMap(),
    val message: String? = null
)

/* ---------- Global product registration ---------- */

data class GlobalProductRegisterRequest(
    val name: String,
    val variant: String?,
    val hsn_code: String?
)

data class GlobalProductRegisterResponse(
    val success: Boolean = true,
    val global_id: Int? = null,
    val name: String,
    val variant: String? = null,
    val hsn_code: String? = null,
    val message: String? = null
)
