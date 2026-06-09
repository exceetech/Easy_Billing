package com.example.easy_billing.network

/**
 * Sync DTOs for shop-defined custom product categories.
 *
 * `POST categories/sync` pushes local rows with no serverId and returns
 * a `local_id → server_id` map, mirroring the product sync contract.
 */
data class CategorySyncRequest(
    val categories: List<CategoryDto>
)

data class CategoryDto(
    val local_id: Int,
    val name: String
)

data class CategorySyncResponse(
    val success_count: Int = 0,
    val category_id_map: Map<String, Int> = emptyMap(),
    val message: String? = null
)

/** Pull list of categories for seeding a fresh device. */
data class CategoryListResponse(
    val categories: List<CategoryItem> = emptyList()
)

data class CategoryItem(
    val id: Int,
    val name: String
)
