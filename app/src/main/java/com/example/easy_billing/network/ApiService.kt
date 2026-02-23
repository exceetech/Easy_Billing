package com.example.easy_billing.network

import retrofit2.http.*

data class LoginResponse(
    val access_token: String,
    val token_type: String,
    val is_first_login: Boolean
)

interface ApiService {

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
}