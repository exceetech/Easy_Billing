package com.example.easy_billing.network

/**
 * Network DTOs for the Supplier master.
 *
 *  • `GET suppliers` → full pull for seeding a fresh device, most
 *    recently used first.
 *  • `GET suppliers/by-gstin?gstin=` → identity lookup, single record.
 *  • `GET suppliers/by-name?name=` → returns a **list**, never one row.
 *    A trade name can belong to several suppliers (different GSTIN /
 *    branch), so the caller must not autofill unless exactly one comes
 *    back — guessing puts the wrong state on the invoice, which flips
 *    CGST+SGST to IGST.
 *  • `POST suppliers/sync` → batch push; server UPSERTS by
 *    (shop_id, gstin), returns local_id → server_id. Field conflicts
 *    resolve by latest updated_at.
 *  • `POST suppliers/account` → single upsert (convenience).
 */
data class SupplierDto(
    val local_id: Int,
    val name: String,
    val gstin: String? = null,
    val state: String? = null,
    val state_code: String? = null,
    val last_used_at: Long = 0L,
    val updated_at: Long = 0L
)

data class SupplierSyncRequest(
    val suppliers: List<SupplierDto>
)

data class SupplierSyncResponse(
    val success_count: Int = 0,
    val supplier_id_map: Map<String, Int> = emptyMap(),
    val message: String? = null
)

data class SupplierRemote(
    val id: Int,
    val name: String,
    val gstin: String? = null,
    val state: String? = null,
    val state_code: String? = null,
    val last_used_at: Long = 0L,
    val updated_at: Long = 0L
)

data class SupplierListResponse(
    val suppliers: List<SupplierRemote> = emptyList()
)

data class SupplierLookupResponse(
    val found: Boolean = false,
    val supplier: SupplierRemote? = null
)

/** Name lookups are plural on purpose — see the note above. */
data class SupplierMatchResponse(
    val suppliers: List<SupplierRemote> = emptyList()
)

/** Single-supplier upsert payload (POST suppliers/account). */
data class SupplierAccountRequest(
    val name: String = "",
    val gstin: String? = null,
    val state: String? = null,
    val state_code: String? = null,
    val last_used_at: Long = 0L,
    val updated_at: Long = 0L
)
