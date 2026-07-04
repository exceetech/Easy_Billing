package com.example.easy_billing.model

import com.example.easy_billing.db.Product
import com.example.easy_billing.db.ProductDao
import java.io.Serializable

data class CartItem(
    val product: Product,
    var quantity: Double,
    var discountAmount: Double = 0.0
) : Serializable {

    fun subTotal(): Double {
        return (product.price * quantity) - discountAmount
    }
}