package com.example.easy_billing.network

data class RegisterRequest(
    val shop_name: String,
    val owner_name: String,
    val email: String,
    val phone: String
)