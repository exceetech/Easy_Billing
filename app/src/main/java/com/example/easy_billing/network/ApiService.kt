package com.example.easy_billing.network

import retrofit2.http.*

data class LoginResponse(
    val access_token: String,
    val token_type: String,
    val is_first_login: Boolean
)

interface ApiService {

    // ================= AUTH =================
    @FormUrlEncoded
    @POST("auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String
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
    ): MessageResponse

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


// ================= REPORTS =================

    @GET("reports/daily")
    suspend fun getDailyReport(
        @Header("Authorization") token: String
    ): List<DailyReportResponse>

    @GET("reports/monthly")
    suspend fun getMonthlyReport(
        @Header("Authorization") token: String
    ): List<MonthlyReportResponse>

    @GET("reports/yearly")
    suspend fun getYearlyReport(
        @Header("Authorization") token: String
    ): List<YearlyReportResponse>

    @GET("reports/peak-hours")
    suspend fun getPeakHours(
        @Header("Authorization") token: String
    ): List<PeakHourResponse>

    @GET("reports/average-bill")
    suspend fun getAverageBill(
        @Header("Authorization") token: String
    ): AverageBillResponse

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
}