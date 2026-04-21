package com.example.easy_billing

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.ProductProfitRaw

class TopProductsFragment : Fragment(R.layout.fragment_top_products) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ProfitAdapter

    private var pendingData: List<ProductProfitRaw>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.rvProfit)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        adapter = ProfitAdapter()
        recyclerView.adapter = adapter

        // ✅ APPLY PENDING DATA IF EXISTS
        pendingData?.let {
            adapter.submitList(it)
            pendingData = null
        }
    }

    fun updateList(data: List<ProductProfitRaw>) {

        if (::adapter.isInitialized) {
            adapter.submitList(data)
        } else {
            // 🔥 STORE UNTIL READY
            pendingData = data
        }
    }
}