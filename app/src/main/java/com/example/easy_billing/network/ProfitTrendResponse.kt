package com.example.easy_billing.network

// Response for GET /profit/trend -- one bucket per hour/day/month
// depending on which period chip was selected on the Profit Analytics
// page. See pos-backend app/routes/profit_routes.py's get_profit_trend
// for the bucketing rules.
data class ProfitTrendResponse(
    val filter: String,
    val buckets: List<ProfitTrendBucket>,
    val total_profit: Double
)

data class ProfitTrendBucket(
    val label: String,
    val start: String,
    val end: String,
    val revenue: Double,
    val cost: Double,
    val loss: Double,
    val profit: Double
)
