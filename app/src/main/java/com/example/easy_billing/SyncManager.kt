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

        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

        val token = prefs.getString("TOKEN", null) ?: return
        val shopId = prefs.getInt("SHOP_ID", 1)

        val txns = db.creditTransactionDao().getUnsynced(shopId)

        for (txn in txns) {

            try {

                // ✅ FIX: pass shopId
                val account = db.creditAccountDao().getById(txn.accountId, shopId)

                if (account?.serverId == null || account.serverId == -1) {
                    println("⏳ Waiting for account sync → txnId=${txn.id}")
                    continue
                }

                val res = api.syncCredit(
                    "Bearer $token",
                    CreditSyncRequest(
                        account_id = account.serverId,
                        amount = txn.amount,
                        type = txn.type
                    )
                )

                if (res.isSuccessful) {
                    db.creditTransactionDao().markSynced(txn.id, shopId)
                } else {
                    println("❌ ERROR: ${res.code()} ${res.errorBody()?.string()}")
                    continue
                }

            } catch (e: Exception) {
                e.printStackTrace()
                continue
            }
        }
    }

    suspend fun syncAccounts() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

        val token = prefs.getString("TOKEN", null) ?: return
        val shopId = prefs.getInt("SHOP_ID", 1)

        // ✅ FIX: filter by shopId
        val accounts = db.creditAccountDao().getAll(shopId)
            .filter { !it.isSynced }

        for (acc in accounts) {
            try {
                val res = api.createCreditAccount(
                    "Bearer $token",
                    CreateCreditAccountRequest(acc.name, acc.phone)
                )

                db.creditAccountDao().updateServerId(
                    acc.id,
                    res.id,
                    shopId
                )

            } catch (e: Exception) {
                e.printStackTrace()
                continue
            }
        }
    }

    suspend fun pullAccountsFromServer() {

        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api

        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

        val token = prefs.getString("TOKEN", null) ?: return
        val shopId = prefs.getInt("SHOP_ID", 1)

        try {

            val accounts = api.getCreditAccounts("Bearer $token")

            for (acc in accounts) {

                val existing = db.creditAccountDao()
                    .getByServerId(acc.id, shopId)

                if (existing != null) {
                    db.creditAccountDao().insert(
                        existing.copy(
                            name = acc.name,
                            phone = acc.phone,
                            dueAmount = acc.due_amount,
                            isActive = true
                        )
                    )
                } else {
                    db.creditAccountDao().insert(
                        CreditAccount(
                            serverId = acc.id,
                            name = acc.name,
                            phone = acc.phone,
                            dueAmount = acc.due_amount,
                            isSynced = true,
                            shopId = shopId,
                            isActive = true
                        )
                    )
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}