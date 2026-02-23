package com.example.easy_billing

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RegisterRequest
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch

class RegisterActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        val etFullName = findViewById<EditText>(R.id.etFullName)
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPhoneNumber = findViewById<EditText>(R.id.etPhoneNumber)
        val etShopName = findViewById<EditText>(R.id.ShopName)
        val btnRegister = findViewById<Button>(R.id.btnRegister)
        val tvBackToLogin = findViewById<TextView>(R.id.tvBackToLogin)

        btnRegister.setOnClickListener {

            val name = etFullName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val phone = etPhoneNumber.text.toString().trim()
            val shopName = etShopName.text.toString().trim()

            // Validation
            if (name.isEmpty() || email.isEmpty() || phone.isEmpty() || shopName.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Enter valid email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {

                    val response = RetrofitClient.api.register(
                        RegisterRequest(
                            shop_name = shopName,
                            owner_name = name,
                            email = email,
                            phone = phone
                        )
                    )

                    Toast.makeText(
                        this@RegisterActivity,
                        response.message,
                        Toast.LENGTH_LONG
                    ).show()

                    finish()

                } catch (e: Exception) {
                    e.printStackTrace()

                    Toast.makeText(
                        this@RegisterActivity,
                        e.message ?: "Unknown Error",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        tvBackToLogin.setOnClickListener {
            finish()
        }
    }
}