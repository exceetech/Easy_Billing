package com.example.easy_billing.network

// Payment & cash-flow tracking: the Money Book sync.

data class MoneyEntryDto(
    val id: Int = 0,                 // server row id (pull only)
    val origin: String? = "",        // install that wrote the row (pull only)
    val uid: String,
    val local_id: Int = 0,
    val direction: String,
    val mode: String,
    val amount: Double,
    val reason: String,
    val source_type: String,
    val source_id: Int = 0,
    val parent_type: String,
    val parent_id: Int = 0,
    val party_name: String = "",
    val ref_text: String = "",
    val status: String = "RECEIVED",
    val created_at: Long = 0L
)

data class MoneyEntrySyncRequest(
    val origin: String = "",
    val entries: List<MoneyEntryDto>
)

data class MoneyEntrySyncResponse(
    val uids: List<String> = emptyList(),
    val message: String? = null
)

data class MoneyEntryPullResponse(
    val entries: List<MoneyEntryDto> = emptyList(),
    val has_more: Boolean = false
)
