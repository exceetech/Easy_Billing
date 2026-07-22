package com.example.easy_billing.db

import com.example.easy_billing.util.appNow

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "credit_transactions")
data class CreditTransaction(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val accountId: Int,

    val shopId: Int,

    val amount: Double,
    // ADD / PAY / SETTLE / PURCHASE_CREDIT / PURCHASE_RETURN / WRITE_OFF / REFUND
    // and the bill-adjustment types:
    //   SALE_RETURN  a credit note put back on the account  → lowers debt
    //   BILL_CANCEL  a cancelled credit bill                → lowers debt
    //   DEBIT_NOTE   an extra charge on a credit bill        → raises debt
    val type: String,

    val referenceInvoice: String? = null,

    /**
     * Local `bills.id` this transaction was generated from, when it came from a
     * bill event (the ADD on a credit sale, or a SALE_RETURN / BILL_CANCEL /
     * DEBIT_NOTE adjusting that sale). Null for manual entries (PAY, SETTLE).
     *
     * This is the *local* id, never the bill number: the id exists the instant
     * the bill is inserted and never changes, whereas the number is a
     * placeholder until the server assigns it. It is what lets an adjustment
     * find how much of its bill is still on credit, and what stops the same
     * document being charged to the account twice.
     */
    val billId: Int? = null,

    /**
     * Local `purchase_table.id` this transaction came from, on the purchase
     * side — the credit purchase itself (PURCHASE_CREDIT), or a return / debit
     * note / cancellation adjusting it. Null for sales rows and manual entries.
     *
     * The mirror of [billId]: the local id, never the invoice number, so it is
     * stable from the moment the purchase is inserted. It lets a purchase
     * adjustment find how much of its invoice is still owed to the supplier,
     * and stops one document being applied to the account twice.
     */
    val purchaseId: Int? = null,

    /**
     * Stable id of the exact document that produced this row, used to enforce
     * "one adjustment per document" — the guard against charging twice on a
     * double-tap, a sync retry, or a re-opened screen.
     *
     * A bill can legitimately have several of these (partial credit notes, an
     * extra debit note), so the key can't be billId+type; it must name the
     * individual document. Format is "<KIND>:<localId>", e.g. "CN:123" for
     * credit-note row 123, "CANCEL:42" for the cancellation of bill 42,
     * "SALE:42" for the original credit sale. Null for manual entries.
     */
    val sourceDoc: String? = null,

    val timestamp: Long = appNow(),

    val isSynced: Boolean = false
)