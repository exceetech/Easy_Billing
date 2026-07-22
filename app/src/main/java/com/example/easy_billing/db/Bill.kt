package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bills")
data class Bill(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val billNumber: String,
    val date: String,
    val subTotal: Double,
    val gst: Double,
    val discount: Double,
    val total: Double,
    val paymentMethod: String,

    val customerType: String = "B2C",
    val customerGstin: String? = null,
    val placeOfSupply: String = "",
    val supplyType: String = "intrastate",
    val cgstAmount: Double = 0.0,
    val sgstAmount: Double = 0.0,
    val igstAmount: Double = 0.0,

    @ColumnInfo(name = "is_synced")
    var isSynced: Boolean = false,

    // ── Cancellation (v23) ────────────────────────────────────────────
    /** True when the bill has been cancelled for GST reporting. */
    @ColumnInfo(name = "is_cancelled")
    val isCancelled: Boolean = false,

    /** Epoch millis when cancellation was confirmed. */
    @ColumnInfo(name = "cancelled_at")
    val cancelledAt: Long? = null,

    // ── N3 (v42) ──────────────────────────────────────────────────────
    /**
     * True once the void has been acknowledged by the server's
     * /bills/cancel endpoint. Stops syncBillCancellations from
     * re-pushing every cancelled bill on every sync cycle forever.
     */
    @ColumnInfo(name = "cancel_synced")
    var cancelSynced: Boolean = false,

    // ── Credit link (v51) ─────────────────────────────────────────────
    /**
     * Local `credit_accounts.id` this bill was charged to, when it was a
     * credit sale. Null for cash bills, and for older credit bills created
     * before this field existed.
     *
     * This is what lets a credit note / debit note / cancellation later ask
     * "was this bill on credit, and to whom?" — which the bill number and the
     * amount alone can never answer.
     */
    @ColumnInfo(name = "credit_account_id")
    val creditAccountId: Int? = null
)