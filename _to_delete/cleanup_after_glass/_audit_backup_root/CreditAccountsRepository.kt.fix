package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.CreditAccount
import com.example.easy_billing.db.CreditTransaction
import com.example.easy_billing.network.CreateCreditAccountRequest
import com.example.easy_billing.network.CreateCreditAccountResponse
import com.example.easy_billing.network.RetrofitClient

/**
 * Data-access layer for CreditAccountsActivity, extracted 1:1 from the DAO
 * and Retrofit calls that used to live inline in the Activity. Every method
 * here is a thin passthrough — no behavior change from what was already
 * happening, just moved out of the Activity so the screen isn't doing its
 * own persistence/network wiring.
 *
 * Phase 1 of this extraction wires up only the read path (getAll/search,
 * used by loadAccounts()/setupSearch()). The write paths (add/pay/settle/
 * delete) still call db/RetrofitClient directly in the Activity for now —
 * this repository already has the methods ready for that next step once the
 * read path is confirmed working.
 */
class CreditAccountsRepository(context: Context) {

    private val db = AppDatabase.getDatabase(context)

    suspend fun getAll(shopId: Int): List<CreditAccount> =
        db.creditAccountDao().getAll(shopId)

    suspend fun search(query: String, shopId: Int): List<CreditAccount> =
        db.creditAccountDao().search(query, shopId)

    suspend fun getByPhone(phone: String, shopId: Int): CreditAccount? =
        db.creditAccountDao().getByPhone(phone, shopId)

    suspend fun getById(id: Int, shopId: Int): CreditAccount? =
        db.creditAccountDao().getById(id, shopId)

    suspend fun insertLocal(account: CreditAccount) =
        db.creditAccountDao().insert(account)

    suspend fun createAccountRemote(
        token: String,
        request: CreateCreditAccountRequest
    ): CreateCreditAccountResponse =
        RetrofitClient.api.createCreditAccount(token, request)

    suspend fun restoreAccount(phone: String, name: String, isSynced: Boolean, shopId: Int) =
        db.creditAccountDao().restoreAccount(
            phone = phone,
            name = name,
            isSynced = isSynced,
            shopId = shopId
        )

    suspend fun addToDue(accountId: Int, delta: Double, shopId: Int) =
        db.creditAccountDao().addToDue(accountId, delta, shopId)

    suspend fun updateDue(accountId: Int, value: Double, shopId: Int) =
        db.creditAccountDao().updateDue(accountId, value, shopId)

    suspend fun insertTransaction(tx: CreditTransaction) =
        db.creditTransactionDao().insert(tx)

    suspend fun deactivateRemote(token: String, serverId: Int) =
        RetrofitClient.api.deactivateCreditAccount(token, serverId)

    suspend fun deactivateLocal(accountId: Int, shopId: Int) =
        db.creditAccountDao().deactivate(accountId, shopId)
}
