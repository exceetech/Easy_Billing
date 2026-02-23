package com.example.easy_billing.network

import retrofit2.http.*

data class LoginResponse(
    val access_token: String,
    val token_type: String
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
}