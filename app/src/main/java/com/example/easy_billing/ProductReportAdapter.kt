package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.TopProductResponse

class ProductReportAdapter(
    private val data: List<TopProductResponse>
) : RecyclerView.Adapter<ProductReportAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvProduct: TextView = view.findViewById(R.id.tvProduct)
        val tvQuantity: TextView = view.findViewById(R.id.tvQuantity)
        val tvRevenue: TextView = view.findViewById(R.id.tvRevenue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {

        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product_report, parent, false)

        return ViewHolder(view)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val r = data[position]

        holder.tvProduct.text = r.product
        holder.tvQuantity.text = "Sold: ${r.quantity}"
        holder.tvRevenue.text = "₹ %.2f".format(r.revenue)
    }
}