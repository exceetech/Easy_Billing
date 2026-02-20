package com.example.easy_billing

import PreviousBillsAdapter
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import kotlinx.coroutines.launch

class PreviousBillsActivity : BaseActivity() {

    private lateinit var rvBills: RecyclerView
    private lateinit var adapter: PreviousBillsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_previous_bills)

        setupToolbar(R.id.toolbar)

        rvBills = findViewById(R.id.rvBills)
        rvBills.layoutManager = LinearLayoutManager(this)

        adapter = PreviousBillsAdapter { bill ->
            val intent = Intent(this, BillDetailsActivity::class.java)
            intent.putExtra("BILL_ID", bill.id)
            startActivity(intent)
        }

        rvBills.adapter = adapter

        loadBills()
    }

    private fun loadBills() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@PreviousBillsActivity)
            val bills = db.billDao().getAllBills()
            adapter.submitList(bills)
        }
    }
}