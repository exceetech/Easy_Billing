package com.example.easy_billing.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.model.CartItem

class CartAdapter(
    private val cartItems: MutableList<CartItem>,
    private val onCartUpdated: () -> Unit
) : RecyclerView.Adapter<CartAdapter.CartViewHolder>() {

    class CartViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val qty: TextView = view.findViewById(R.id.tvQty)
        val price: TextView = view.findViewById(R.id.tvPrice)
        val btnPlus: Button = view.findViewById(R.id.btnPlus)
        val btnMinus: Button = view.findViewById(R.id.btnMinus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CartViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cart, parent, false)
        return CartViewHolder(view)
    }

    override fun onBindViewHolder(holder: CartViewHolder, position: Int) {
        val item = cartItems[position]

        holder.name.text = item.product.name
        holder.qty.text = item.quantity.toString()
        holder.price.text = "₹${item.subTotal()}"

        holder.btnPlus.setOnClickListener {

            val currentPosition = holder.adapterPosition
            if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener

            cartItems[currentPosition].quantity++
            notifyItemChanged(currentPosition)
            onCartUpdated()
        }

        holder.btnMinus.setOnClickListener {

            val currentPosition = holder.adapterPosition
            if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener

            val currentItem = cartItems[currentPosition]
            currentItem.quantity--

            if (currentItem.quantity <= 0) {
                cartItems.removeAt(currentPosition)
                notifyItemRemoved(currentPosition)
            } else {
                notifyItemChanged(currentPosition)
            }

            onCartUpdated()
        }
    }

    override fun getItemCount() = cartItems.size
}