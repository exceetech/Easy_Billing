package com.example.easy_billing.network

data class ExistingProduct(

    val id: Int,

    val price: Double,

    val variant: String?,

    val unit: String,

    val has_inventory: Boolean,

    val stock: Double,

    val avg_cost: Double,

    val is_active: Boolean

)