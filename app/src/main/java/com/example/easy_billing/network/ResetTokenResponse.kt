package com.example.easy_billing.network

data class ResetTokenResponse(
    val reset_token: String,
    val token_type: String
)