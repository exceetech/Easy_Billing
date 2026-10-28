package com.example.easy_billing.util

/**
 * Credit accounts are ONE shared ledger for customers and suppliers - there is no "type" column.
 * Whether a payment on an account is money IN (a customer paying dues) or money OUT (paying a
 * supplier) is therefore read from the account's own history. Pure logic, unit-tested on the JVM.
 */
object CreditSide {

    enum class Side { CUSTOMER, SUPPLIER, UNKNOWN }

    private val CUSTOMER_TYPES = setOf("ADD", "SALE_RETURN", "BILL_CANCEL", "DEBIT_NOTE")
    private val SUPPLIER_TYPES = setOf("PURCHASE_CREDIT", "PURCHASE_RETURN")

    /** UNKNOWN when the history is empty (or only payments) or mixes both kinds. */
    fun of(transactionTypes: Collection<String>): Side {
        val customer = transactionTypes.any { it in CUSTOMER_TYPES }
        val supplier = transactionTypes.any { it in SUPPLIER_TYPES }
        return when {
            customer && !supplier -> Side.CUSTOMER
            supplier && !customer -> Side.SUPPLIER
            else -> Side.UNKNOWN
        }
    }

    /** What this account owes YOU (>= 0). A supplier you overpaid owes you; a customer owes you their due. */
    fun receivable(due: Double, side: Side): Double =
        if (side == Side.SUPPLIER) maxOf(-due, 0.0) else maxOf(due, 0.0)

    /** What YOU owe this account (>= 0). A supplier's due, or a customer's advance. */
    fun payable(due: Double, side: Side): Double =
        if (side == Side.SUPPLIER) maxOf(due, 0.0) else maxOf(-due, 0.0)

    /** Direction of a "Record a payment" on this side; null when the owner must say. */
    fun payDirection(side: Side): String? = when (side) {
        Side.CUSTOMER -> MoneyDirection.IN
        Side.SUPPLIER -> MoneyDirection.OUT
        Side.UNKNOWN -> null
    }

    /** Direction of handing back an advance (settling a negative balance). */
    fun refundDirection(side: Side): String? = when (side) {
        Side.CUSTOMER -> MoneyDirection.OUT      // shop gives the customer's advance back
        Side.SUPPLIER -> MoneyDirection.IN       // supplier gives back what we overpaid
        Side.UNKNOWN -> null
    }

    fun payReason(direction: String) =
        if (direction == MoneyDirection.IN) MoneyReason.DUES_RECEIVED else MoneyReason.SUPPLIER_PAID

    fun refundReason(direction: String) =
        if (direction == MoneyDirection.OUT) MoneyReason.ADVANCE_REFUND else MoneyReason.PURCHASE_REFUND
}
