package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class ProductAdapter(
    private val onItemClick: (Product) -> Unit,
    private val onItemLongClick: (Product) -> Unit
) : ListAdapter<Product, ProductAdapter.ProductViewHolder>(DiffCallback()) {

    // Full list for filtering
    private var fullList: List<Product> = emptyList()

    // ==================================================
    // ================= VIEW HOLDER ====================
    // ==================================================

    inner class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tvProductName)
        private val price: TextView = view.findViewById(R.id.tvProductPrice)

        fun bind(product: Product) {
            name.text = product.name
            price.text = "₹${product.price}"

            itemView.setOnClickListener { onItemClick(product) }
            itemView.setOnLongClickListener {
                onItemLongClick(product)
                true
            }
        }
    }

    // ==================================================
    // ================= ADAPTER ========================
    // ==================================================

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product, parent, false)
        return ProductViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun updateData(newList: List<Product>) {
        fullList = newList
        submitList(newList)
    }

    // ==================================================
    // ================= FILTERING ======================
    // ==================================================

    fun filter(query: String) {

        if (query.isBlank()) {
            submitList(fullList)
            return
        }

        val filtered = fullList.filter {
            it.name.contains(query.trim(), ignoreCase = true)
        }

        submitList(filtered)
    }

    // ==================================================
    // ================= DIFF UTIL ======================
    // ==================================================

    class DiffCallback : DiffUtil.ItemCallback<Product>() {

        override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean {
            return oldItem == newItem
        }
    }
}