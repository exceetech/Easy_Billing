package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inventory_log")
data class InventoryLog(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val productId: Int,

    val type: String,
    // "ADD", "SALE", "LOSS", "ADJUST"

    val quantity: Double,

    val price: Double,
    // cost price for ADD/LOSS, selling price for SALE

    val date: Long,
    val isSynced: Boolean = false,

    // Avg-cost audit, Fix 2: the average cost of this product AFTER this
    // event was fully applied (including any batch-ledger recompute /
    // drift reconciliation that happened in the same transaction) — i.e.
    // the true final number, computed once, on the phone. Synced up so the
    // backend can store this value directly instead of re-deriving average
    // cost with its own separate copy of the formula. Defaults to 0.0 only
    // for pre-migration rows (which are already synced and never re-read).
    val resultingAverageCost: Double = 0.0
)