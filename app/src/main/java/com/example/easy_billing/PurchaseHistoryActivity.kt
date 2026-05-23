package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.viewmodel.PurchaseHistoryViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * PurchaseHistoryActivity
 *
 * Shows a paginated list of all recorded purchase invoices.
 * Tapping an invoice opens [PurchaseDetailsActivity] where the user
 * can review line items and raise a debit note (purchase return).
 */
class PurchaseHistoryActivity : AppCompatActivity() {

    private val viewModel: PurchaseHistoryViewModel by viewModels()

    private lateinit var rvPurchases: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: PurchaseHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_purchase_history)

        rvPurchases = findViewById(R.id.rvPurchases)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty     = findViewById(R.id.tvEmpty)

        adapter = PurchaseHistoryAdapter(emptyList()) { purchase ->
            val intent = Intent(this, PurchaseDetailsActivity::class.java).apply {
                putExtra("PURCHASE_ID", purchase.id)
            }
            startActivity(intent)
        }
        rvPurchases.layoutManager = LinearLayoutManager(this)
        rvPurchases.adapter       = adapter

        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // Reload on resume so returned items are reflected immediately
        viewModel.loadPurchases()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.purchases.collectLatest { list ->
                adapter.update(list)
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
