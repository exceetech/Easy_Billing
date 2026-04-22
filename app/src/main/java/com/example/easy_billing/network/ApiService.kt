package com.example.easy_billing.network

import com.example.easy_billing.db.CreditTransaction
import retrofit2.Response
import retrofit2.http.*

data class LoginResponse(
    val access_token: String,
    val token_type: String,
    val is_first_login: Boolean,
    val shop_id: Int
)

interface ApiService {

    // ================= AUTH =================

    @FormUrlEncoded
    @POST("auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Header("device-id") deviceId: String
    ): LoginResponse

    @POST("auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): RegisterResponse

    @GET("auth/me")
    suspend fun getProfile(
        @Header("Authorization") token: String
    ): ProfileResponse

    @FormUrlEncoded
    @POST("auth/change-password")
    suspend fun changePassword(
        @Header("Authorization") token: String,
        @Field("new_password") newPassword: String
    ): retrofit2.Response<Unit>

    @POST("auth/verify-password")
    suspend fun verifyPassword(
        @Header("Authorization") token: String,
        @Body request: VerifyPasswordRequest
    ): retrofit2.Response<VerifyPasswordResponse>

    // ================= PRODUCTS =================

    @GET("products/catalog")
    suspend fun getCatalog(
        @Header("Authorization") token: String
    ): List<GlobalProductResponse>

    @GET("products/my-products")
    suspend fun getMyProducts(
        @Header("Authorization") token: String
    ): List<ShopProductResponse>

    @POST("products/add-to-shop")
    suspend fun addProductToShop(
        @Header("Authorization") token: String,
        @Body request: AddProductRequest
    ): AddProductResponse

    @PUT("products/deactivate/{id}")
    suspend fun deactivateProduct(
        @Header("Authorization") token: String,
        @Path("id") id: Int
    )


    // ================= BILL =================

    @POST("bills/create")
    suspend fun createBill(
        @Header("Authorization") token: String,
        @Body request: CreateBillRequest
    ): CreateBillResponse

    @GET("bills/{id}")
    suspend fun getBillDetails(
        @Header("Authorization") token: String,
        @Path("id") billId: Int
    ): BillDetailResponse

    @GET("bills")
    suspend fun getBills(
        @Header("Authorization") token: String,
        @Query("date") date: String? = null,
        @Query("item") item: String? = null,
        @Query("payment") payment: String? = null,
        @Query("sort") sort: String? = null
    ): List<BillResponse>


    // ================= REPORTS =================

    @GET("reports/daily")
    suspend fun getDailyReport(
        @Header("Authorization") token: String,
        @Query("type") type: String? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): List<DailyReportResponse>

    @GET("reports/weekly")
    suspend fun getWeeklyReport(
        @Header("Authorization") token: String
    ): List<WeeklyReportResponse>

    @GET("reports/monthly")
    suspend fun getMonthlyReport(
        @Header("Authorization") token: String
    ): List<MonthlyReportResponse>

    @GET("reports/yearly")
    suspend fun getYearlyReport(
        @Header("Authorization") token: String
    ): List<YearlyReportResponse>

    @GET("reports/custom")
    suspend fun getCustomReport(
        @Header("Authorization") token: String,
        @Query("start_date") start: String,
        @Query("end_date") end: String
    ): List<SalesTrendResponse>


    // ================= PRODUCT ANALYTICS =================

    @GET("reports/top-products")
    suspend fun getTopProducts(
        @Header("Authorization") token: String,
        @Query("type") type: String,
        @Query("start") start: String?,
        @Query("end") end: String?
    ): List<TopProductResponse>

    @GET("reports/top-revenue-products")
    suspend fun getTopRevenueProducts(
        @Header("Authorization") token: String
    ): List<TopRevenueProductResponse>

    @GET("reports/today-hourly")
    suspend fun getTodayHourlySales(
        @Header("Authorization") token: String
    ): List<PeakHourResponse>


    // ================= TIME ANALYTICS =================

    @GET("reports/peak-hours")
    suspend fun getPeakHours(
        @Header("Authorization") token: String,
        @Query("type") type: String,
        @Query("start_date") start: String? = null,
        @Query("end_date") end: String? = null
    ): List<PeakHourResponse>

    @GET("reports/weekday-analysis")
    suspend fun getWeekdayAnalysis(
        @Header("Authorization") token: String
    ): List<WeekdayAnalysisResponse>

    @GET("reports/heatmap")
    suspend fun getHeatmap(
        @Header("Authorization") token: String
    ): List<HeatmapResponse>


    // ================= BUSINESS ANALYTICS =================

    @GET("reports/payment-analysis")
    suspend fun getPaymentAnalysis(
        @Header("Authorization") token: String
    ): List<PaymentAnalysisResponse>

    @GET("reports/average-bill")
    suspend fun getAverageBill(
        @Header("Authorization") token: String,
        @Query("type") type: String,
        @Query("start_date") start: String? = null,
        @Query("end_date") end: String? = null
    ): AverageBillResponse

    @GET("reports/trend")
    suspend fun getSalesTrend(
        @Header("Authorization") token: String
    ): List<SalesTrendResponse>


    // ================= EMAIL REPORT =================

    @POST("reports/email-report")
    suspend fun sendEmailReport(
        @Header("Authorization") token: String,
        @Query("type") type: String,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): MessageResponse

    // ================= SHOP SETTINGS =================

    @GET("shop/settings")
    suspend fun getStoreSettings(
        @Header("Authorization") token: String
    ): ShopSettingsResponse


    @PUT("shop/settings")
    suspend fun updateStoreSettings(
        @Header("Authorization") token: String,
        @Body request: ShopSettingsUpdateRequest
    ): Map<String, String>

    @GET("billing-settings")
    suspend fun getBillingSettings(
        @Header("Authorization") token: String
    ): BillingSettingsResponse


    @PUT("billing-settings")
    suspend fun updateBillingSettings(
        @Header("Authorization") token: String,
        @Body request: BillingSettingsUpdateRequest
    ): Map<String,String>

    @PUT("security/clear-bills")
    suspend fun clearBills(
        @Header("Authorization") token: String
    )

    @PUT("security/factory-reset")
    suspend fun factoryReset(
        @Header("Authorization") token: String
    )

    @PUT("security/change-password")
    suspend fun changePassword(
        @Header("Authorization") token: String,
        @Body request: ChangePasswordRequest
    )

    @POST("auth/save-token")
    suspend fun saveFcmToken(
        @Header("Authorization") token: String,
        @Body request: SaveTokenRequest
    ): Response<Unit>

    @GET("analytics/ai-report")
    suspend fun getAiReport(
        @Header("Authorization") token: String
    ): AiReportResponse

    @POST("auth/forgot-password")
    suspend fun forgotPassword(
        @Body request: ForgotPasswordRequest
    ): ForgotPasswordResponse

    @POST("auth/verify-otp")
    suspend fun verifyOtp(
        @Query("email") email: String,
        @Query("otp") otp: String
    ): Response<VerifyOtpResponse>

    @POST("auth/reset-password")
    suspend fun resetPassword(
        @Header("Authorization") token: String,
        @Body request: ChangePasswordRequest
    ): Response<Unit>


// ================= USER =================

    @GET("subscription/")
    suspend fun getSubscription(
        @Header("Authorization") token: String
    ): SubscriptionResponse


    // ================= ADMIN =================

    @POST("subscription/admin/activate")
    suspend fun adminActivateSubscription(
        @Query("shop_id") shopId: Int,
        @Query("plan") plan: String
    ): MessageResponse

    // ================= CREDIT =================

    @POST("credit/sync")
    suspend fun syncCredit(
        @Header("Authorization") token: String,
        @Body request: CreditSyncRequest
    ): Response<Map<String, String>>

    @POST("credit/account")
    suspend fun createCreditAccount(
        @Header("Authorization") token: String,
        @Body request: CreateCreditAccountRequest
    ): CreditAccountResponse

    @GET("credit/accounts")
    suspend fun getCreditAccounts(
        @Header("Authorization") token: String
    ): List<CreditAccountResponse>

    @GET("credit/transactions/{accountId}")
    suspend fun getTransactions(
        @Header("Authorization") token: String,
        @Path("accountId") accountId: Int
    ): List<CreditTransactionResponse>

    @PATCH("credit/account/{id}/deactivate")
    suspend fun deactivateCreditAccount(
        @Header("Authorization") token: String,
        @Path("id") id: Int
    ): Response<Unit>

    @PATCH("credit/reset")
    suspend fun resetCredit(
        @Header("Authorization") token: String
    ): Response<Unit>

    // ================= INVENTORY =================

    @POST("inventory/sync")
    suspend fun syncInventory(
        @Header("Authorization") token: String,
        @Body logs: List<InventoryLogRequest>
    ): Response<MessageResponse>


    @GET("inventory/my")
    suspend fun getInventory(
        @Header("Authorization") token: String
    ): List<InventoryResponse>
}