package com.example.easy_billing.network

data class CheckProductResponse(

    val exists: Boolean,

    val product: ExistingProduct?

)