package com.example.easy_billing.network

data class DailyReportResponse(
    val date: String,
    val sum: Double
)

data class MonthlyReportResponse(
    val month: String,
    val revenue: Double,
    val total_bills: Int
)

data class YearlyReportResponse(
    val year: Int,
    val revenue: Double,
    val total_bills: Int
)

data class PeakHourResponse(
    val hour: Int,
    val total_bills: Int,
    val revenue: Double
)

data class AverageBillResponse(
    val average_bill: Double,
    val total_revenue: Double,
    val total_bills: Int
)