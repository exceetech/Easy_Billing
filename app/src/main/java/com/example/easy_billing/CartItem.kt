package com.example.easy_billing.model

import com.example.easy_billing.Product
import com.example.easy_billing.db.ProductDao

data class CartItem(
    val product: Product,
    var quantity: Int
) {
    fun subTotal(): Double = product.price * quantity
}