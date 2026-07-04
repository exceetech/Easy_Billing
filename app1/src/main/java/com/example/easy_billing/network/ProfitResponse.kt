package com.example.easy_billing.network

data class ProfitResponse(
    val summary: ProfitSummary,
    val products: List<ProfitProduct>
)