package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.easy_billing.DashboardActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etUsername = findViewById<EditText>(R.id.etUsername)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvRegister = findViewById<TextView>(R.id.tvRegister)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)


        val prefs = getSharedPreferences("easy_billing_prefs", MODE_PRIVATE)

        // 👇 EXISTENCE CHECK HAPPENS HERE
        if (!prefs.contains("USERNAME")) {
            // First time app is opened EVER
            prefs.edit()
                .putString("USERNAME", "adeeb")
                .putString("PASSWORD", "1111")   // temp password
                .putBoolean("FIRST_LOGIN", true)
                .apply()
        }

        tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        btnLogin.setOnClickListener {

            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val savedUsername = prefs.getString("USERNAME", "")
            val savedPassword = prefs.getString("PASSWORD", "")
            val isFirstLogin = prefs.getBoolean("FIRST_LOGIN", false)

            if (username == savedUsername && password == savedPassword) {

                if (isFirstLogin) {
                    // 🚀 FIRST TIME LOGIN → CHANGE PASSWORD
                    startActivity(
                        Intent(this, ChangePasswordActivity::class.java)
                    )
                    finish()
                } else {
                    // ✅ NORMAL LOGIN → DASHBOARD
                    val intent = Intent(this, DashboardActivity::class.java)
                    intent.putExtra("USER_NAME", username)
                    startActivity(intent)
                    finish()
                }

            } else {
                Toast.makeText(this, "Invalid Credentials", Toast.LENGTH_SHORT).show()
            }
        }

        tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
    }
}