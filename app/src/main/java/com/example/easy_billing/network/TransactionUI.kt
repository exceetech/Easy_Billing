package com.example.easy_billing

data class TransactionUI(
    val type: String,
    val amount: Double,
    val timestamp: Long,
    val runningBalance: Double,
    val isHeader: Boolean = false,
    val headerTitle: String = ""
)