package com.example.easy_billing.network

data class VerifyOtpResponse(
    val otp_verified: Boolean,
    val email: String,
    val access_token: String,
    val token_type: String
)