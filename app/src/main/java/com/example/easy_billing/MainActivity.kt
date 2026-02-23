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

            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {

                    val response = RetrofitClient.api.login(username, password)

                    val token = response.access_token

                    getSharedPreferences("auth", MODE_PRIVATE)
                        .edit {
                            putString("TOKEN", token)
                        }

                    if (response.is_first_login) {

                        val intent = Intent(this@MainActivity, ChangePasswordActivity::class.java)
                        startActivity(intent)
                        finish()

                    } else {

                        startActivity(Intent(this@MainActivity, DashboardActivity::class.java))
                        finish()
                    }

                } catch (e: Exception) {

            if (e is retrofit2.HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                Toast.makeText(
                    this@MainActivity,
                    "HTTP ${e.code()} : $errorBody",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    e.message ?: "Unknown error",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
            }
        }

        // 🔹 Forgot password
        tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
    }
}