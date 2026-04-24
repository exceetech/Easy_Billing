package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.easy_billing.db.ProductProfitRaw
import com.example.easy_billing.network.ProfitResponse
import com.example.easy_billing.network.RetrofitClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

class ProfitActivity : BaseActivity() {

    private lateinit var adapter: ProfitPagerAdapter

    private lateinit var btnToday: MaterialButton
    private lateinit var btnWeek: MaterialButton
    private lateinit var btnMonth: MaterialButton
    private lateinit var btnAll: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profit)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        adapter = ProfitPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = if (pos == 0) "Charts" else "Products"
        }.attach()

        btnToday = findViewById(R.id.btnToday)
        btnWeek = findViewById(R.id.btnWeek)
        btnMonth = findViewById(R.id.btnMonth)
        btnAll = findViewById(R.id.btnAll)

        btnToday.setOnClickListener { loadProfit("today") }
        btnWeek.setOnClickListener { loadProfit("week") }
        btnMonth.setOnClickListener { loadProfit("month") }
        btnAll.setOnClickListener { loadProfit("all") }

        loadProfit("all")
    }

    private fun loadProfit(filter: String) {

        lifecycleScope.launch {

            try {

                val token = getSharedPreferences("auth", MODE_PRIVATE)
                    .getString("TOKEN", null)

                val response: ProfitResponse =
                    RetrofitClient.api.getProfit(
                        "Bearer $token",
                        filter,
                        null,
                        null
                    )

                val summary = response.summary

                findViewById<TextView>(R.id.tvRevenue).text =
                    "₹${"%.2f".format(summary.revenue)}"

                findViewById<TextView>(R.id.tvCost).text =
                    "₹${"%.2f".format(summary.cost)}"

                findViewById<TextView>(R.id.tvNetProfit).text =
                    "₹${"%.2f".format(summary.profit)}"

                findViewById<TextView>(R.id.tvLoss).text =
                    "₹${"%.2f".format(summary.loss)}"

                findViewById<TextView>(R.id.tvExpense).text =
                    "₹${"%.2f".format(summary.expense)}"

                val mapped = response.products.map {

                    ProductProfitRaw(
                        productName = it.product_name,
                        variant = it.variant,
                        totalQty = it.qty,
                        revenue = it.revenue,
                        cost = it.cost,
                        profit = it.profit,
                        added = it.added,
                        sold = it.sold,
                        remaining = it.remaining,
                        lossQty = it.lossQty,
                        lossAmount = it.lossAmount
                    )
                }

                adapter.topFragment.updateList(mapped)
                adapter.chartFragment.updateChart(mapped)



            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
