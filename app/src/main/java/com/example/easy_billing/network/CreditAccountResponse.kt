package com.example.easy_billing.network

/**
 * A credit account as returned by `GET /credit/accounts`.
 *
 * That route returns the whole database row, so every field here really is
 * present. Do NOT reuse it for `POST /credit/account` — see
 * [CreateCreditAccountResponse].
 */
data class CreditAccountResponse(
    val id: Int,
    val name: String,
    val phone: String,
    val due_amount: Double,

    val shop_id: Int,
    val is_active: Boolean
)

/**
 * The reply to `POST /credit/account`.
 *
 * The create route builds its response by hand and sends only these fields —
 * no `shop_id`, no `is_active`. It used to be parsed as [CreditAccountResponse],
 * which declares both as non-null. Gson quietly left them at 0 and false and
 * nothing read them, so it worked by luck: a converter that enforces non-null
 * fields (Moshi, kotlinx.serialization) would have thrown on every account
 * creation — and the failure would have been invisible, because each caller
 * catches it and silently falls back to saving the account offline.
 *
 * [restored] is true when the server reactivated a previously deleted account
 * rather than creating a new one, in which case [due_amount] is the balance
 * that account already carried.
 */
data class CreateCreditAccountResponse(
    val id: Int,
    val name: String,
    val phone: String,
    val due_amount: Double = 0.0,
    val restored: Boolean = false
)
