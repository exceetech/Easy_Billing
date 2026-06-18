package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.ProductProfitRaw

/**
 * Profit-by-product chart page: a ranked "leaderboard" of products by net profit
 * (replaces the old MPAndroidChart vertical bar chart with rotated x-axis labels).
 */
class ProfitChartActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profit_chart)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayHomeAsUpEnabled(true)
        }
        // Themed back arrow (matches the rest of the app).
        toolbar.setNavigationIcon(R.drawable.ic_back_arrow)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val rv = findViewById<RecyclerView>(R.id.rvChart)
        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)
        val tvTotal = findViewById<TextView>(R.id.tvTotalProfit)
        val tvBest = findViewById<TextView>(R.id.tvBestProduct)

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val data = (intent.getSerializableExtra("DATA") as? ArrayList<ProductProfitRaw>)
            ?: arrayListOf()

        // Rank by net profit (highest first), keep the top 10.
        val ranked = data.sortedByDescending { it.profit }.take(10)

        if (ranked.isEmpty()) {
            rv.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            tvTotal.text = "₹0"
            tvBest.text = "—"
            return
        }

        val total = ranked.sumOf { it.profit }
        tvTotal.text = "₹${"%,.2f".format(total)}"
        tvTotal.setTextColor(Color.parseColor(if (total < 0) "#A32D2D" else "#0F6E56"))

        val best = ranked.first()
        tvBest.text = if (best.variant.isNullOrBlank()) best.productName
            else "${best.productName} (${best.variant})"

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = ProfitChartAdapter(ranked)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
