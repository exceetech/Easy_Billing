package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch
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

        // 🔹 Register
        tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // 🔹 Login
        btnLogin.setOnClickListener {

            val email = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {

                    // Call backend login
                    val response = RetrofitClient.api.login(
                        username = email,
                        password = password
                    )

                    val token = response.access_token

                    // Save JWT token
                    getSharedPreferences("auth", MODE_PRIVATE)
                        .edit {
                            putString("TOKEN", token)
                        }

                    Toast.makeText(
                        this@MainActivity,
                        "Login Successful",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Go to dashboard
                    val intent = Intent(this@MainActivity, DashboardActivity::class.java)
                    intent.putExtra("USER_NAME", email)
                    startActivity(intent)
                    finish()

                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Login Failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // 🔹 Forgot password
        tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
    }
}