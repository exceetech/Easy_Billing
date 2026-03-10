package com.example.easy_billing.network

data class AiReportResponse(

    val report_data: List<ProductReport>,

    val ai_report: String
)

data class ProductReport(

    val product: String,

    val quantity: Int,

    val revenue: Double
)