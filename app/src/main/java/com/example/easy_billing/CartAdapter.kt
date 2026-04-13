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
import com.example.easy_billing.util.CurrencyHelper

class CartAdapter(
    private val items: MutableList<CartItem>,
    private val onQuantityChanged: () -> Unit,
    private val onDelete: (CartItem) -> Unit
) : RecyclerView.Adapter<CartAdapter.CartViewHolder>() {

    private val MAX_QTY = 10000.0

    inner class CartViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvQty: TextView = view.findViewById(R.id.tvQty)
        val tvPrice: TextView = view.findViewById(R.id.tvPrice)
        val btnPlus: ImageButton = view.findViewById(R.id.btnPlus)
        val btnMinus: ImageButton = view.findViewById(R.id.btnMinus)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CartViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cart, parent, false)
        return CartViewHolder(view)
    }

    override fun onBindViewHolder(holder: CartViewHolder, position: Int) {

        val item = items[position]
        val context = holder.itemView.context
        val product = item.product

        holder.tvName.text = product.name

        val qty = item.quantity
        val unit = product.unit?.lowercase() ?: "unit"

        // ✅ FORMAT QUANTITY
        val formattedQty = if (qty % 1 == 0.0) {
            qty.toInt().toString()
        } else {
            String.format("%.2f", qty).trimEnd('0').trimEnd('.')
        }

        // ✅ DISPLAY WITH UNIT
        holder.tvQty.text = when (unit) {
            "kilogram" -> "$formattedQty kg"
            "litre" -> "$formattedQty L"
            "gram" -> "$formattedQty g"
            "millilitre" -> "$formattedQty ml"
            "piece" -> "x$formattedQty"
            else -> "$formattedQty $unit"
        }

        // ✅ PRICE
        holder.tvPrice.text =
            CurrencyHelper.format(context, item.subTotal())

        // ✅ STEP (CORRECT)
        val step = when (unit) {
            "kilogram", "litre" -> 0.25
            "gram", "millilitre" -> 50.0
            else -> 1.0
        }

        // ================= PLUS =================
        setupHoldButton(holder.btnPlus) {

            highlightItem(holder.itemView)
            holder.btnPlus.pressAnim()

            val newQty = item.quantity + step

            if (newQty > MAX_QTY) {
                android.widget.Toast.makeText(
                    context,
                    "Max quantity reached",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setupHoldButton
            }

            item.quantity = newQty

            holder.tvQty.text = formatQty(item.quantity, unit)
            holder.tvPrice.text = CurrencyHelper.format(context, item.subTotal())

            onQuantityChanged()
        }

        // ================= MINUS =================
        setupHoldButton(holder.btnMinus) {

            highlightItem(holder.itemView)
            holder.btnMinus.pressAnim()

            val newQty = item.quantity - step

            if (newQty <= 0) {
                onDelete(item)
                return@setupHoldButton
            }

            item.quantity = newQty

            holder.tvQty.text = formatQty(item.quantity, unit)
            holder.tvPrice.text = CurrencyHelper.format(context, item.subTotal())

            onQuantityChanged()
        }

        // ================= DELETE =================
        holder.btnDelete.setOnClickListener {
            onDelete(item)
        }
    }

    override fun getItemCount(): Int = items.size

    // ================= PRO BUTTON HANDLER =================

    fun setupHoldButton(
        button: View,
        onClick: () -> Unit
    ) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var isHolding = false

        val runnable = object : Runnable {
            override fun run() {
                if (isHolding) {
                    onClick()
                    handler.postDelayed(this, 120) // speed of hold 🔥
                }
            }
        }

        button.setOnClickListener {
            button.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }

        button.setOnLongClickListener {
            isHolding = true
            handler.post(runnable)
            true
        }

        button.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP ||
                event.action == android.view.MotionEvent.ACTION_CANCEL
            ) {
                isHolding = false
            }
            false
        }
    }

    fun formatQty(qty: Double, unit: String): String {
        val formatted = if (qty % 1 == 0.0) {
            qty.toInt().toString()
        } else {
            String.format("%.2f", qty).trimEnd('0').trimEnd('.')
        }

        return when (unit) {
            "kilogram", "kg" -> "$formatted kg"
            "litre", "l" -> "$formatted L"
            "gram" -> "$formatted g"
            "millilitre" -> "$formatted ml"
            "piece" -> "x$formatted"
            else -> "$formatted $unit"
        }
    }

    fun highlightItem(view: View) {
        view.animate()
            .scaleX(1.03f)
            .scaleY(1.03f)
            .setDuration(120)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
            }
    }

    fun View.pressAnim() {
        this.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70)
            .withEndAction {
                this.animate().scaleX(1f).scaleY(1f).setDuration(70)
            }
    }
}