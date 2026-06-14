package com.example.easy_billing.network

data class DailyReportResponse(
    val date: String,
    val revenue: Double,
    val bills: Int
)

data class MonthlyReportResponse(
    val month: String,
    val revenue: Double,
    val bills: Int
)

data class YearlyReportResponse(
    val year: Int,
    val revenue: Double,
    val bills: Int
)

data class PeakHourResponse(
    val date: String,
    val hour: Int,
    val bills: Int,
    val revenue: Double
)

data class AverageBillResponse(
    val average_bill: Double,
    val total_revenue: Double,
    val total_bills: Int,

    val prev_revenue: Double,
    val prev_bills: Int,
    val prev_avg: Double
)

data class TopProductResponse(
    val product: String,
    val variant: String?,
    val unit: String,
    val quantity: Double,   // I2 FIX: was Int — truncated fractional units (kg, L)
    val frequency: Int,
    val revenue: Double
)

data class WeeklyReportResponse(
    val week: String,
    val revenue: Double,
    val bills: Int
)

data class WeekdayAnalysisResponse(
    val weekday: Int,
    val bills: Int,
    val revenue: Double
)

data class HeatmapResponse(
    val weekday: Int,
    val hour: Int,
    val revenue: Double,
    val bills: Int
)

data class PaymentAnalysisResponse(
    val payment_method: String,
    val bills: Int,
    val revenue: Double
)

data class TopRevenueProductResponse(
    val product: String,
    val revenue: Double
)

data class SalesTrendResponse(
    val date: String,
    val hour: Int,
    val revenue: Double,
    val month: String
)

data class PaymentSplitItem(
    val method: String,
    val bills: Int,
    val revenue: Double,
    val percent: Int
)

data class OverviewResponse(
    val total_revenue: Double,
    val total_bills: Int,
    val average_bill: Double,
    val returns_total: Double,
    val cancelled_count: Int,
    val cancelled_amount: Double,
    val payment_split: List<PaymentSplitItem>,
    val sparkline: List<Double>,
    val prev_revenue: Double,
    val prev_bills: Int,
    val prev_avg: Double
)
