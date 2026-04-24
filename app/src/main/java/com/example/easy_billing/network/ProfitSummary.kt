package com.example.easy_billing.network

data class ProfitSummary(
    val revenue: Double,
    val cost: Double,
    val profit: Double,
    val loss: Double,
    val expense: Double
)