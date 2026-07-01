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
    private lateinit var etSearch: com.google.android.material.textfield.TextInputEditText

    private var fullList: List<ProductProfitRaw> = emptyList()
    private var pendingData: List<ProductProfitRaw>? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.rvProfit)
        etSearch = view.findViewById(R.id.etSearchProfit)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        //adapter = ProfitAdapter()
        recyclerView.adapter = adapter

        setupSearch()

        // ✅ APPLY PENDING DATA
        pendingData?.let {
            fullList = it
            adapter.submitList(it)
            pendingData = null
        }
    }

    // ================= SEARCH =================

    private fun setupSearch() {

        etSearch.addTextChangedListener(object : android.text.TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                // 🔥 CANCEL PREVIOUS (debounce)
                searchRunnable?.let { handler.removeCallbacks(it) }

                searchRunnable = Runnable {

                    val query = s?.toString()?.trim()?.lowercase() ?: ""

                    if (query.isEmpty()) {
                        adapter.submitList(fullList)
                    } else {

                        val filtered = fullList.filter {

                            it.productName.lowercase().contains(query) ||
                                    (it.variant?.lowercase()?.contains(query) ?: false)
                        }

                        adapter.submitList(filtered)
                    }
                }

                handler.postDelayed(searchRunnable!!, 300)
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    // ================= DATA =================

    fun updateList(data: List<ProductProfitRaw>) {

        fullList = data

        if (::adapter.isInitialized) {
            adapter.submitList(data)
        } else {
            pendingData = data
        }
    }
}