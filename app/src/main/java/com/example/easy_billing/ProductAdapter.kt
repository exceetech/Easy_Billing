package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.util.CurrencyHelper
import com.google.android.material.card.MaterialCardView
import com.example.easy_billing.util.PastelColor
import com.example.easy_billing.util.GoogleTranslator
import kotlinx.coroutines.*

class ProductAdapter(
    private val onItemClick: (Product) -> Unit,
    private val onItemLongClick: (Product) -> Unit,
    private val language: String,
    private val translationEnabled: Boolean
) : ListAdapter<Product, ProductAdapter.ProductViewHolder>(DiffCallback()) {

    private var fullList: List<Product> = emptyList()

    // ==================================================
    // ================= VIEW HOLDER ====================
    // ==================================================

    inner class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val name: TextView = view.findViewById(R.id.tvProductName)
        private val translatedName: TextView =
            view.findViewById(R.id.tvProductNameTranslated)

        private val price: TextView = view.findViewById(R.id.tvProductPrice)
        private val card: MaterialCardView = view.findViewById(R.id.cardView)

        fun bind(product: Product) {

            // Original name
            name.text = product.name

            // Hide translation if English
            if (!translationEnabled || language == "en") {

                translatedName.visibility = View.GONE

            } else {

                translatedName.visibility = View.VISIBLE
                translatedName.text = "..."

                GlobalScope.launch(Dispatchers.IO) {

                    val translated =
                        GoogleTranslator.translate(product.name, language)

                    withContext(Dispatchers.Main) {
                        translatedName.text = translated
                    }
                }
            }

            // Price
            val context = itemView.context
            price.text = CurrencyHelper.format(context, product.price)

            // Random pastel color
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