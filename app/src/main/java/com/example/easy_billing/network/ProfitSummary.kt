package com.example.easy_billing.network

data class ProfitSummary(
    val revenue: Double,
    val cost: Double,
    val profit: Double,
    val loss: Double,
    val expense: Double,
    // Moving-average redesign, Phase 5: net gain/loss booked on purchase
    // returns (Debit Notes). Already folded into `profit`; kept as its
    // own field so a report screen can show it as a distinct line item.
    // Positive = loss, negative = gain. Defaults to 0.0 for older backends
    // that predate this field.
    val purchaseReturnVariance: Double = 0.0,
    val growth: ProfitGrowth? = null
)

data class ProfitGrowth(
    val profit_percentage: Double
)