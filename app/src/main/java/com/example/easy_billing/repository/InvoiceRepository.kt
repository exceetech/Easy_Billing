package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.BillingSettings
import com.example.easy_billing.db.Customer
import com.example.easy_billing.db.CreditAccount
import com.example.easy_billing.db.StoreInfo
import com.example.easy_billing.network.CreateCreditAccountRequest
import com.example.easy_billing.network.RetrofitClient

/**
 * Data-access layer for InvoiceActivity's PURE I/O functions only:
 * loadStoreInfo, loadBillingSettings, lookupCustomerByPhone,
 * upsertCustomerMaster, generatePdfAndPrint's reads, handleCreditFlow's
 * customer-list read, showAddCustomerDialog's CRUD. Thin 1:1 passthroughs —
 * no behavior change.
 *
 * Deliberately NOT included: saveBill() and anything it touches. saveBill()
 * interleaves DAO calls with GST calculation, stock-deduction ordering, and
 * a credit-debt apply-then-clear correctness guarantee (see its own
 * comments on `pendingCreditAccount` and `isBillSaved`). Mechanically moving
 * those calls into a repository risks breaking the sequencing that keeps
 * a bill's numbers and stock/credit side-effects consistent. That function
 * stays as-is; if it's ever split up it needs a dedicated, careful pass —
 * not a bulk wrap like this one.
 */
class InvoiceRepository(context: Context) {

    private val db = AppDatabase.getDatabase(context)

    // ===== Customer lookup / upsert =====

    suspend fun getCustomerByPhoneAndType(phone: String, type: String, shopId: Int): Customer? =
        db.customerDao().getByPhoneAndType(phone, type, shopId)

    suspend fun getCustomerByPhoneRemote(token: String, phone: String, type: String) =
        RetrofitClient.api.getCustomerByPhone("Bearer $token", phone, type)

    suspend fun insertCustomer(customer: Customer) =
        db.customerDao().insert(customer)

    suspend fun updateCustomer(customer: Customer) =
        db.customerDao().update(customer)

    // ===== Print =====

    suspend fun getBillById(billId: Int) =
        db.billDao().getBillById(billId)

    suspend fun getItemsForBill(billId: Int) =
        db.billDao().getItemsForBill(billId)

    suspend fun getGstInvoiceByBillId(billId: Int) =
        db.gstSalesInvoiceDao().getByBillId(billId)

    // ===== Store info =====

    suspend fun getStoreInfo(): StoreInfo? =
        db.storeInfoDao().get()

    suspend fun insertStoreInfo(store: StoreInfo) =
        db.storeInfoDao().insert(store)

    suspend fun getStoreSettingsRemote(token: String) =
        RetrofitClient.api.getStoreSettings(token)

    suspend fun getGstProfile() =
        db.gstProfileDao().get()

    // ===== Billing settings =====

    suspend fun getBillingSettings(): BillingSettings? =
        db.billingSettingsDao().get()

    suspend fun getBillingSettingsRemote(token: String) =
        RetrofitClient.api.getBillingSettings(token)

    suspend fun insertBillingSettings(settings: BillingSettings) =
        db.billingSettingsDao().insert(settings)

    // ===== Credit customer picker / add-customer dialog =====

    suspend fun getAllCreditAccounts(shopId: Int): List<CreditAccount> =
        db.creditAccountDao().getAll(shopId)

    suspend fun getCreditAccountByPhone(phone: String, shopId: Int): CreditAccount? =
        db.creditAccountDao().getByPhone(phone, shopId)

    suspend fun createCreditAccountRemote(token: String, request: CreateCreditAccountRequest) =
        RetrofitClient.api.createCreditAccount(token, request)

    suspend fun insertCreditAccount(account: CreditAccount) =
        db.creditAccountDao().insert(account)
}
