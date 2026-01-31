package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.ProductAdapter
import com.example.easy_billing.db.AppDatabase
import kotlinx.coroutines.launch
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.adapter.CartAdapter

class DashboardActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private val cartItems = mutableListOf<CartItem>()
    private lateinit var cartAdapter: CartAdapter
    private lateinit var tvTotal: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
        val tvWelcome = findViewById<TextView>(R.id.tvWelcome)
        val btnAdmin = findViewById<Button>(R.id.btnAdmin)
        val btnLogout = findViewById<Button>(R.id.btnLogout)

        // Welcome message
        val userName = intent.getStringExtra("USER_NAME")
        tvWelcome.text = "Welcome, $userName"

        recyclerView = findViewById(R.id.rvProducts)
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        val rvCart = findViewById<RecyclerView>(R.id.rvCart)
        tvTotal = findViewById(R.id.tvTotal)

        cartAdapter = CartAdapter(cartItems) {
            updateTotal()
        }
        rvCart.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvCart.adapter = cartAdapter

        btnAdmin.setOnClickListener {
            startActivity(Intent(this, AddProductActivity::class.java))
            drawerLayout.closeDrawers()
        }

        btnLogout.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        loadProducts()
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@DashboardActivity)
            val products = db.productDao().getAllProducts()

            runOnUiThread {
                Toast.makeText(
                    this@DashboardActivity,
                    "Products loaded: ${products.size}",
                    Toast.LENGTH_LONG
                ).show()

                recyclerView.adapter = ProductAdapter(products) { product ->

                    val existingItem = cartItems.find { it.product.id == product.id }

                    if (existingItem != null) {
                        existingItem.quantity++
                    } else {
                        cartItems.add(CartItem(product, 1))
                    }

                    cartAdapter.notifyDataSetChanged()
                    updateTotal()
                }
            }
        }
    }
    private fun updateTotal() {
        val total = cartItems.sumOf { it.subTotal() }
        tvTotal.text = "Total: ₹$total"
    }
}