package com.example.easy_billing.network

// Stage 3D: allocations / PDN pools / customer-return claims.

data class AllocationDto(
    val id: Int = 0,                 // server row id (pull only)
    val origin: String? = "",       // install that wrote the row (pull only)
    val local_id: Int,
    val source_type: String,
    val source_id: Int,
    val bill_number: String?,
    val line_ordinal: Int?,
    val product_id: Int,
    val purchase_invoice_id: Int?,
    val batch_local_id: Int?,
    val quantity: Double,
    val quantity_restored: Double,
    val unit_cost_net: Double,
    val unit_cost_gross: Double,
    val created_at: Long
)

data class PoolDto(
    val id: Int = 0,
    val origin: String? = "",
    val local_id: Int,
    val note_local_id: Int,
    val purchase_invoice_id: Int,
    val product_id: Int,
    val gone_qty: Double,
    val delta_gross: Double,
    val note_date: Long
)

data class ClaimDto(
    val id: Int = 0,
    val origin: String? = "",
    val local_id: Int,
    val allocation_local_id: Int,
    val pool_local_id: Int,
    val note_local_id: Int,
    val product_id: Int?,
    val qty: Double,
    val amount: Double,
    val claim_date: Long,
    val source_type: String,
    val source_id: Int,
    val bill_number: String? = null
)

data class SaleAllocationSyncRequest(
    val origin: String = "",
    val allocations: List<AllocationDto>,
    val pools: List<PoolDto>,
    val claims: List<ClaimDto>
)

data class SaleAllocationSyncResponse(
    val allocation_ids: List<Int> = emptyList(),
    val pool_ids: List<Int> = emptyList(),
    val claim_ids: List<Int> = emptyList(),
    val message: String? = null
)

data class SaleAllocationPullResponse(
    val allocations: List<AllocationDto> = emptyList(),
    val pools: List<PoolDto> = emptyList(),
    val claims: List<ClaimDto> = emptyList()
)
