package com.example.easy_billing

/**
 * One row in the customer ledger, or a date header.
 *
 * [isSynced] and [reference] carry information the row can show but the raw
 * transaction type cannot: whether an entry has reached the server yet, and the
 * invoice it came from. Both default to the harmless case, so any caller that
 * doesn't set them is unaffected.
 */
data class TransactionUI(
    val type: String,
    val amount: Double,
    val timestamp: Long,
    val runningBalance: Double,
    val isHeader: Boolean = false,
    val headerTitle: String = "",

    /** False for a transaction still waiting to be pushed to the server. */
    val isSynced: Boolean = true,

    /** Invoice number this entry came from, where one exists. */
    val reference: String? = null
)
