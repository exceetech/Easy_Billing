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

    // Deliberately NOT a BaseActivity (avoids BaseActivity's forced landscape
    // re-orientation), so the system bars are hidden locally here instead —
    // same immersive treatment every other screen in the app gets.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

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
