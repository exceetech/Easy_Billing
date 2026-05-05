package com.example.easy_billing

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Product
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.GoogleTranslator
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.*

class ProductAdapter(
    private val onItemClick: (Product) -> Unit,
    private val onItemLongClick: (Product) -> Unit,
    private val language: String,
    private val translationEnabled: Boolean
) : ListAdapter<Product, ProductAdapter.ProductViewHolder>(DiffCallback()) {

    private var fullList: List<Product> = emptyList()
    private var inventoryMap: Map<Int, Double> = emptyMap()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Premium card palette (High-contrast pastels) - Optimized for light theme
    private val cardPastels = listOf(
        "#FFE4E6", "#DCFCE7", "#DBEAFE", "#FEF9C3", "#F3E8FF",
        "#E0E7FF", "#D1FAE5", "#FFEDD5", "#CCFBF1", "#FCE7F3"
    )

    // Bold accent colors for monograms (High contrast)
    private val monogramAccents = listOf(
        "#E11D48", "#059669", "#2563EB", "#CA8A04", "#7C3AED",
        "#4F46E5", "#047857", "#D97706", "#0D9488", "#DB2777"
    )

    private fun getStableIndex(name: String): Int = 
        kotlin.math.abs(name.hashCode()) % cardPastels.size

    fun setInventoryMap(map: Map<Int, Double>) {
        inventoryMap = map
        notifyDataSetChanged()
    }

    fun updateData(newList: List<Product>) {
        fullList = newList
        submitList(newList)
    }

    fun filter(query: String) {
        if (query.isBlank()) {
            submitList(fullList)
            return
        }
        val q = query.trim()
        submitList(fullList.filter {
            it.name.contains(q, ignoreCase = true) ||
                    it.variant?.contains(q, ignoreCase = true) == true
        })
    }

    fun cancelScope() {
        scope.cancel()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product, parent, false)
        return ProductViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.cardView)
        private val name: TextView         = view.findViewById(R.id.tvProductName)
        private val translated: TextView   = view.findViewById(R.id.tvTranslatedName)
        private val variant: TextView      = view.findViewById(R.id.tvVariantName)
        private val price: TextView        = view.findViewById(R.id.tvProductPrice)
        private val unit: TextView         = view.findViewById(R.id.tvProductUnit)
        private val stockDot: View         = view.findViewById(R.id.viewStockDot)
        private val stock: TextView        = view.findViewById(R.id.tvStock)
        private val lowBadge: TextView     = view.findViewById(R.id.tvLowStockBadge)
        private val overlay: FrameLayout   = view.findViewById(R.id.flOutOfStockOverlay)
        private val monogram: TextView     = view.findViewById(R.id.tvProductMonogram)
        private val monogramBg: View       = view.findViewById(R.id.viewProductMonogramBg)

        private var boundName: String = ""

        fun bind(product: Product) {
            val context = itemView.context
            val colorIdx = getStableIndex(product.name)

            card.alpha          = 1f
            card.isClickable    = true
            stock.visibility    = View.GONE
            stockDot.visibility = View.GONE
            overlay.visibility  = View.GONE
            lowBadge.visibility = View.GONE

            // ── Premium Pastel Concept ──────────────────────────────────
            card.setCardBackgroundColor(Color.parseColor(cardPastels[colorIdx]))

            // ── Monogram Styling ─────────────────────────────────────────
            val firstLetter = product.name.take(1).uppercase()
            monogram.text = firstLetter
            monogramBg.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(monogramAccents[colorIdx])
            )
            monogram.setTextColor(Color.WHITE)

            boundName = product.name
            name.text = product.name

            val variantText = product.variant?.takeIf { it.isNotBlank() }
            variant.visibility = if (variantText != null) View.VISIBLE else View.GONE
            variant.text       = variantText ?: ""

            if (translationEnabled && language != "en") {
                translated.visibility = View.VISIBLE
                translated.text       = "…"
                val snapshot = product.name
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        GoogleTranslator.translate(snapshot, language)
                    }
                    if (boundName == snapshot) translated.text = result
                }
            } else {
                translated.visibility = View.GONE
            }

            val unitLabel = formatUnit(product.unit?.takeIf { it.isNotBlank() } ?: "unit")
            price.text = CurrencyHelper.format(context, product.price)
            unit.text  = "per $unitLabel"

            val stockEntry = if (product.trackInventory) inventoryMap[product.id] else null

            when {
                stockEntry == null -> {
                    setClickListeners(product)
                }
                stockEntry <= 0 -> {
                    overlay.visibility  = View.VISIBLE
                    card.alpha          = 0.55f
                    card.isClickable    = false
                    itemView.setOnClickListener {
                        Toast.makeText(context, "Out of stock", Toast.LENGTH_SHORT).show()
                    }
                    itemView.setOnLongClickListener {
                        onItemLongClick(product)
                        true
                    }
                }
                stockEntry <= 5 -> {
                    lowBadge.visibility = View.VISIBLE
                    stockDot.visibility = View.VISIBLE
                    stockDot.background.setTint(0xFFEF9F27.toInt())
                    stock.visibility = View.VISIBLE
                    stock.text = "${String.format("%.2f", stockEntry)} $unitLabel left"
                    stock.setBackgroundResource(R.drawable.bg_stock_orange)
                    stock.setTextColor(0xFF92400E.toInt())
                    setClickListeners(product)
                }
                else -> {
                    stockDot.visibility = View.VISIBLE
                    stockDot.background.setTint(0xFF10B981.toInt())
                    stock.visibility = View.VISIBLE
                    stock.text = "${String.format("%.2f", stockEntry)} $unitLabel"
                    stock.setBackgroundResource(R.drawable.bg_stock_green)
                    stock.setTextColor(0xFF065F46.toInt())
                    setClickListeners(product)
                }
            }
        }

        private fun setClickListeners(product: Product) {
            itemView.setOnClickListener { onItemClick(product) }
            itemView.setOnLongClickListener { onItemLongClick(product); true }
        }
    }

    private fun formatUnit(unit: String): String = when (unit.lowercase()) {
        "piece"  -> "Pc"
        "kg"     -> "Kg"
        "litre"  -> "L"
        "gram"   -> "g"
        "ml"     -> "ml"
        else     -> unit
    }

    class DiffCallback : DiffUtil.ItemCallback<Product>() {
        override fun areItemsTheSame(oldItem: Product, newItem: Product) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Product, newItem: Product) = oldItem == newItem
    }
}