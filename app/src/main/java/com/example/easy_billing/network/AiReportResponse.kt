package com.example.easy_billing.network

data class AiReportResponse(
    val insights: List<AiInsight> = emptyList(),
    val report_data: List<ProductReport> = emptyList(),
    val ai_report: String = ""
)

data class AiInsight(
    val type: String,
    val title: String,
    val description: String,
    val actionText: String? = null,
    val actionType: String? = null
)

data class ProductReport(
    val product: String,
    val quantity: Int,
    val revenue: Double
)