package com.example.easy_billing

import android.graphics.Color
import android.graphics.Color.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import androidx.core.graphics.toColorInt
import com.example.easy_billing.util.PastelColor

class ProductAdapter(
    private val onItemClick: (Product) -> Unit,
    private val onItemLongClick: (Product) -> Unit
) : ListAdapter<Product, ProductAdapter.ProductViewHolder>(DiffCallback()) {

    // Full list for filtering
    private var fullList: List<Product> = emptyList()

    // Soft dashboard colors
//    private val pastelColors = listOf(
//        "#DCEBFF",
//        "#FFE4E8",
//        "#E6F7EC",
//        "#FFF1D6",
//        "#EFE6FF",
//        "#E3F6FF"
//    )

    // ==================================================
    // ================= VIEW HOLDER ====================
    // ==================================================

    inner class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val name: TextView = view.findViewById(R.id.tvProductName)
        private val price: TextView = view.findViewById(R.id.tvProductPrice)
        private val card: MaterialCardView = view.findViewById(R.id.cardView)

        fun bind(product: Product) {

            name.text = product.name
            price.text = "₹${product.price}"

            // Stable color for each product
            val color = PastelColor.random()

            card.setCardBackgroundColor(color)

            itemView.setOnClickListener {
                onItemClick(product)
            }

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

        val product = getItem(position)
        holder.bind(product)
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