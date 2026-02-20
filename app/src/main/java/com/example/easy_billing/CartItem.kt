package com.example.easy_billing.model

import com.example.easy_billing.Product
import com.example.easy_billing.db.ProductDao
import java.io.Serializable

data class CartItem(
    val product: Product,
    var quantity: Int
) : Serializable {

    fun subTotal(): Double {
        return product.price * quantity
    }
}