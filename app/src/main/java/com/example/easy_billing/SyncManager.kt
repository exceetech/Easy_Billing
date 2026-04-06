package com.example.easy_billing.sync

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.BillingSettings
import com.example.easy_billing.db.CreditAccount
import com.example.easy_billing.db.StoreInfo
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.CreateBillRequest
import com.example.easy_billing.network.BillItemRequest
import com.example.easy_billing.network.CreateCreditAccountRequest
import com.example.easy_billing.network.CreditSyncRequest

class SyncManager(private val context: Context) {

    suspend fun syncBills() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        val bills = db.billDao().getUnsyncedBills()

        for (bill in bills) {

            try {

                val items = db.billItemDao().getItemsForBill(bill.id)

                val apiItems = items.map {
                    BillItemRequest(
                        shop_product_id = it.productId,
                        quantity = it.quantity
                    )
                }

                val request = CreateBillRequest(
                    bill_number = "",
                    items = apiItems,
                    payment_method = bill.paymentMethod,
                    discount = bill.discount,
                    gst = bill.gst,
                    total_amount = bill.total
                )

                // 🔥 CALL API
                val response = api.createBill(
                    "Bearer $token",
                    request
                )

                // 🔥 ONLY AFTER SUCCESS
                db.billDao().markBillSynced(bill.id)
                db.billItemDao().markItemsSynced(bill.id)

            } catch (e: Exception) {

                // ❌ STOP LOOP (important)
                break
            }
        }
    }

    suspend fun syncStoreInfo() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        try {

            // ✅ ALWAYS FETCH FROM BACKEND
            val response = api.getStoreSettings("Bearer $token")

            if (!response.shop_name.isNullOrBlank()) {

                val store = StoreInfo(
                    name = response.shop_name,
                    address = response.store_address ?: "",
                    phone = response.phone ?: "",
                    gstin = response.store_gstin ?: "",
                    isSynced = true
                )

                db.storeInfoDao().insert(store)
            }

        } catch (e: Exception) {
            // ignore
        }
    }

    suspend fun syncBillingSettings() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        try {

            val response = api.getBillingSettings("Bearer $token")

            val settings = BillingSettings(
                defaultGst = response.default_gst,
                printerLayout = response.printer_layout
            )

            db.billingSettingsDao().insert(settings)

        } catch (_: Exception) {
            // offline → ignore
        }
    }

    suspend fun syncCredit() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        val txns = db.creditTransactionDao().getUnsynced()

        for (txn in txns) {

            try {

                // ✅ GET ACCOUNT FROM ROOM
                val account = db.creditAccountDao().getById(txn.accountId)

                if (account?.serverId == null) {
                    println("❌ Account not synced yet → txnId=${txn.id}")
                    continue
                }

                // ✅ USE serverId instead of local id
                val res = api.syncCredit(
                    CreditSyncRequest(
                        account_id = account.serverId,
                        amount = txn.amount,
                        type = txn.type
                    )
                )

                if (res.isSuccessful) {
                    db.creditTransactionDao().markSynced(txn.id)
                } else {
                    println("❌ ERROR: ${res.code()} ${res.errorBody()?.string()}")
                    break
                }

            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }
    }

    suspend fun syncAccounts() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        val accounts = db.creditAccountDao().getAll()
            .filter { !it.isSynced }

        for (acc in accounts) {
            try {
                val res = api.createCreditAccount(
                    "Bearer $token",
                    CreateCreditAccountRequest(acc.name, acc.phone)
                )

                db.creditAccountDao().insert(
                    acc.copy(
                        serverId = res.id,
                        isSynced = true
                    )
                )

            } catch (e: Exception) {
                break
            }
        }
    }

    suspend fun pullAccountsFromServer() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return

        try {

            val accounts = api.getCreditAccounts("Bearer $token")

            for (acc in accounts) {
                db.creditAccountDao().insert(
                    CreditAccount(
                        serverId = acc.id,
                        name = acc.name,
                        phone = acc.phone,
                        dueAmount = acc.due_amount,
                        isSynced = true
                    )
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}