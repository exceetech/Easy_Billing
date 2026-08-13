package com.example.easy_billing.network

data class UserEventSyncRequest(
    val events: List<UserEventDto>
)

data class UserEventDto(
    val event_type: String,
    val screen: String?,
    val detail: String?,
    val created_at: Long
)

data class UserEventSyncResponse(
    val success_count: Int = 0,
    val message: String? = null
)
