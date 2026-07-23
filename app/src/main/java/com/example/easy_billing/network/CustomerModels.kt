package com.example.easy_billing.network

/**
 * Network DTOs for the Customer master.
 *
 *  • `GET customers/by-phone?phone=` → single record lookup.
 *  • `GET customers` → full pull for seeding a fresh device.
 *  • `POST customers/sync` → batch push; server UPSERTS by
 *    (shop_id, phone), returns local_id → server_id. Field conflicts
 *    resolve by latest updated_at.
 *  • `POST customers/account` → single upsert (optional convenience).
 */
data class CustomerDto(
    val local_id: Int,
    val phone: String,
    val name: String,
    val customer_type: String,
    val business_name: String? = null,
    val gstin: String? = null,
    val state: String? = null,
    val state_code: String? = null,
    val updated_at: Long,
    // Report 1 S-6: previously never sent, so the customer's credit-account
    // link and soft-delete state didn't propagate across devices.
    // credit_account_id is the account's SERVER id (resolved the same way
    // as bills — see SyncManager.syncCustomers).
    val credit_account_id: Int? = null,
    val is_active: Boolean = true
)

data class CustomerSyncRequest(
    val customers: List<CustomerDto>
)

data class CustomerSyncResponse(
    val success_count: Int = 0,
    val customer_id_map: Map<String, Int> = emptyMap(),
    val message: String? = null
)

data class CustomerRemote(
    val id: Int,
    val phone: String,
    val name: String? = null,
    val customer_type: String? = "B2C",
    val business_name: String? = null,
    val gstin: String? = null,
    val state: String? = null,
    val state_code: String? = null,
    val updated_at: Long = 0L
)

data class CustomerListResponse(
    val customers: List<CustomerRemote> = emptyList()
)

data class CustomerLookupResponse(
    val found: Boolean = false,
    val customer: CustomerRemote? = null
)

/** Single-customer upsert payload (POST customers/account). */
data class CustomerAccountRequest(
    val phone: String,
    val name: String = "",
    val customer_type: String = "B2C",
    val business_name: String? = null,
    val gstin: String? = null,
    val state: String? = null,
    val state_code: String? = null,
    val updated_at: Long = 0L
)
