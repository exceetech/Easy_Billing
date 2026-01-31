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

class DashboardActivity : AppCompatActivity() {

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

        val recyclerView = findViewById<RecyclerView>(R.id.rvProducts)
        recyclerView.layoutManager = GridLayoutManager(this, 3) // 3 columns (POS style)

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@DashboardActivity)
            val products = db.productDao().getAllProducts()

            runOnUiThread {
                recyclerView.adapter = ProductAdapter(products) { product ->
                    // TEMP click action (billing comes next)
                    Toast.makeText(
                        this@DashboardActivity,
                        "${product.name} added",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Admin → Add Products
        btnAdmin.setOnClickListener {
            startActivity(Intent(this, AddProductActivity::class.java))
            drawerLayout.closeDrawers()
        }

        // Logout
        btnLogout.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}