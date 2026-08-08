package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Supplier

private val SUPPLIER_DIFF_CALLBACK = object : DiffUtil.ItemCallback<Supplier>() {
    override fun areItemsTheSame(oldItem: Supplier, newItem: Supplier): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Supplier, newItem: Supplier): Boolean =
        oldItem == newItem
}

/** Rows for the supplier picker sheet. Mirrors [CreditAdapter]. */
class SupplierAdapter(
    initialList: List<Supplier>,
    private val onClick: (Supplier) -> Unit
) : ListAdapter<Supplier, SupplierAdapter.VH>(SUPPLIER_DIFF_CALLBACK) {

    init {
        submitList(initialList)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.tvAvatar)
        val name: TextView = view.findViewById(R.id.tvSupName)
        val gstin: TextView = view.findViewById(R.id.tvSupGstin)
        val state: TextView = view.findViewById(R.id.tvSupState)
        val tag: TextView = view.findViewById(R.id.tvSupTag)
    }

    /** Monogram tiles cycle through the theme's three accent tints. */
    private val tileBg = intArrayOf(
        R.drawable.bg_addp_tile_green,
        R.drawable.bg_tile_gold,
        R.drawable.bg_tile_violet
    )
    private val tileInk = arrayOf("#0F6E56", "#B8895A", "#6C4EA0")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_supplier, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val name = item.name.trim()

        holder.name.text = name
        holder.state.text = item.state

        holder.avatar.text = if (name.isNotEmpty()) name[0].uppercase() else "?"
        val slot = position % tileBg.size
        holder.avatar.backgroundTintList = null
        holder.avatar.setBackgroundResource(tileBg[slot])
        holder.avatar.setTextColor(android.graphics.Color.parseColor(tileInk[slot]))

        // GSTIN is what tells two same-named suppliers apart, so it always
        // has a line of its own — even when there isn't one.
        val gstin = item.gstin
        if (gstin.isNullOrBlank()) {
            holder.gstin.text = "No GSTIN"
            holder.tag.text = "UNREGISTERED"
        } else {
            holder.gstin.text = gstin
            holder.tag.text = "REGISTERED"
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    fun update(newList: List<Supplier>) {
        submitList(newList)
    }
}
