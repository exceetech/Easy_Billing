package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.PastelColor
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_NO
        )

        val tvWelcome = findViewById<TextView>(R.id.tvWelcome)

        typeWriter(
            tvWelcome,
            "Welcome to ExPOS"
        )

        val etUsername = findViewById<EditText>(R.id.etUsername)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvRegister = findViewById<TextView>(R.id.tvRegister)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)

        // pastel cards
        val loginCard = findViewById<MaterialCardView>(R.id.loginCard)
        val usernameCard = findViewById<MaterialCardView>(R.id.usernameCard)
        val passwordCard = findViewById<MaterialCardView>(R.id.passwordCard)

        // Apply random pastel colors
        loginCard.setCardBackgroundColor(PastelColor.random())
        usernameCard.setCardBackgroundColor(PastelColor.random())
        passwordCard.setCardBackgroundColor(PastelColor.random())

        btnLogin.setBackgroundColor(PastelColor.random())

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

                        val intent = Intent(
                            this@MainActivity,
                            ChangePasswordActivity::class.java
                        )
                        startActivity(intent)
                        finish()

                    } else {

                        startActivity(
                            Intent(this@MainActivity, DashboardActivity::class.java)
                        )
                        finish()
                    }

                } catch (e: Exception) {

                    if (e is retrofit2.HttpException) {

                        val errorBody = e.response()?.errorBody()?.string()

                        Toast.makeText(
                            this@MainActivity,
                            errorBody ?: "Login failed",
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

        // forgot password
        tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
    }
    fun typeWriter(textView: TextView, message: String, delay: Long = 40) {

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var index = 0

        handler.post(object : Runnable {
            override fun run() {

                if (index <= message.length) {
                    textView.text = message.substring(0, index)
                    index++
                    handler.postDelayed(this, delay)
                }
            }
        })
    }
}