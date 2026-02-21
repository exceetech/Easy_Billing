package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etUsername = findViewById<EditText>(R.id.etUsername)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvRegister = findViewById<TextView>(R.id.tvRegister)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)

        val prefs = getSharedPreferences("easy_billing_prefs", MODE_PRIVATE)

        // Check whether username exist or not?
        if (!prefs.contains("USERNAME")) {
            // First time app is opened EVER
            prefs.edit {
                putString("USERNAME", "adeeb")
                    .putString("PASSWORD", "1111") // Temporary password
                    .putBoolean("FIRST_LOGIN", true)
            }
        }

        // Register Button
        tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // Login Button
        btnLogin.setOnClickListener {

            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            // Check for empty fields
            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check for valid credentials
            val savedUsername = prefs.getString("USERNAME", "")
            val savedPassword = prefs.getString("PASSWORD", "")
            val isFirstLogin = prefs.getBoolean("FIRST_LOGIN", false)

            // Credentials matched
            if (username == savedUsername && password == savedPassword) {

                // First login, then change password
                if (isFirstLogin) {
                    startActivity(
                        Intent(this, ChangePasswordActivity::class.java)
                    )
                    finish()
                }
                else {
                    // Normal login, then go to dashboard
                    val intent = Intent(this, DashboardActivity::class.java)
                    intent.putExtra("USER_NAME", username)
                    startActivity(intent)
                    finish()
                }

            }
            else {
                Toast.makeText(this, "Invalid Credentials", Toast.LENGTH_SHORT).show()
            }
        }

        tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
    }
}