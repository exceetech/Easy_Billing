package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Product
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

    // 🔥 Scoped coroutine (instead of GlobalScope)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val name: TextView = view.findViewById(R.id.tvProductName)
        private val translatedView: TextView =
            view.findViewById(R.id.tvTranslatedName)
        private val variantView: TextView =
            view.findViewById(R.id.tvVariantName)

        private val price: TextView = view.findViewById(R.id.tvProductPrice)
        private val card: MaterialCardView = view.findViewById(R.id.cardView)

        fun bind(product: Product) {

            // ================= NAME =================
            name.text = product.name

            // ================= VARIANT =================
            val variantText = product.variant?.takeIf { it.isNotBlank() }

            if (variantText != null) {
                variantView.visibility = View.VISIBLE
                variantView.text = variantText
            } else {
                variantView.visibility = View.GONE
            }

            // ================= TRANSLATION =================
            if (translationEnabled && language != "en") {

                translatedView.visibility = View.VISIBLE
                translatedView.text = "..."

                val currentName = product.name

                scope.launch {

                    val translated = withContext(Dispatchers.IO) {
                        GoogleTranslator.translate(currentName, language)
                    }

                    // 🔥 Prevent wrong binding due to recycling
                    if (name.text == currentName) {
                        translatedView.text = translated
                    }
                }

            } else {
                translatedView.visibility = View.GONE
            }

            // ================= PRICE =================
            val context = itemView.context
            val formattedPrice = CurrencyHelper.format(context, product.price)

            val unit = product.unit?.takeIf { it.isNotBlank() } ?: "unit"
            val unitText = formatUnit(unit)

            price.text = "$formattedPrice / $unitText"

            // ================= UI =================
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

    // ================= ADAPTER =================

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

    // ================= FILTER =================

    fun filter(query: String) {

        if (query.isBlank()) {
            submitList(fullList)
            return
        }

        val q = query.trim()

        val filtered = fullList.filter {
            it.name.contains(q, true) ||
                    (it.variant?.contains(q, true) ?: false)
        }

        submitList(filtered)
    }

    private fun formatUnit(unit: String): String {
        return when (unit.lowercase()) {
            "piece" -> "Pc"
            "kg" -> "Kg"
            "litre" -> "L"
            "gram" -> "g"
            "ml" -> "ml"
            else -> unit
        }
    }

    // ================= DIFF =================

    class DiffCallback : DiffUtil.ItemCallback<Product>() {

        override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean {
            return oldItem == newItem
        }
    }
}