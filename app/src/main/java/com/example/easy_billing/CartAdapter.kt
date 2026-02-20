package com.example.easy_billing.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.model.CartItem

class CartAdapter(
    private val items: MutableList<CartItem>,
    private val onQuantityChanged: () -> Unit,
    private val onDelete: (CartItem) -> Unit
) : RecyclerView.Adapter<CartAdapter.CartViewHolder>() {

    inner class CartViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvQty: TextView = view.findViewById(R.id.tvQty)
        val tvPrice: TextView = view.findViewById(R.id.tvPrice)
        val btnPlus: Button = view.findViewById(R.id.btnPlus)
        val btnMinus: Button = view.findViewById(R.id.btnMinus)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CartViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cart, parent, false)
        return CartViewHolder(view)
    }

    override fun onBindViewHolder(holder: CartViewHolder, position: Int) {
        val item = items[position]

        holder.tvName.text = item.product.name
        holder.tvQty.text = item.quantity.toString()
        holder.tvPrice.text = "₹${item.subTotal()}"

        holder.btnPlus.setOnClickListener {
            item.quantity++
            notifyItemChanged(position)
            onQuantityChanged()
        }

        holder.btnMinus.setOnClickListener {
            if (item.quantity > 1) {
                item.quantity--
                notifyItemChanged(position)
                onQuantityChanged()
            }
        }

        holder.btnDelete.setOnClickListener {
            onDelete(item)
        }
    }

    override fun getItemCount(): Int = items.size
}