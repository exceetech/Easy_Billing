package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout

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