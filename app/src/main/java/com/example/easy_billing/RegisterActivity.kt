package com.example.easy_billing

import android.content.Intent
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

    private var isRegistering = false   // 🔥 FLAG TO PREVENT DOUBLE CLICK

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

            // 🔥 PREVENT MULTIPLE CLICKS
            if (isRegistering) return@setOnClickListener
            isRegistering = true
            btnRegister.isEnabled = false
            btnRegister.text = "Registering..."

            val name = etFullName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val phone = etPhoneNumber.text.toString().trim()
            val shopName = etShopName.text.toString().trim()

            if (name.isEmpty() || email.isEmpty() || phone.isEmpty() || shopName.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                resetButton(btnRegister)
                return@setOnClickListener
            }

            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Enter valid email", Toast.LENGTH_SHORT).show()
                resetButton(btnRegister)
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {

                    RetrofitClient.api.register(
                        RegisterRequest(
                            shop_name = shopName,
                            owner_name = name,
                            email = email,
                            phone = phone
                        )
                    )

                    showSuccessDialog(email)

                } catch (e: Exception) {

                    Toast.makeText(
                        this@RegisterActivity,
                        e.message ?: "Error",
                        Toast.LENGTH_SHORT
                    ).show()

                    resetButton(btnRegister)
                }
            }
        }

        tvBackToLogin.setOnClickListener {
            finish()
        }
    }

    // 🔥 RESET BUTTON STATE
    private fun resetButton(button: Button) {
        isRegistering = false
        button.isEnabled = true
        button.text = "Register"
    }

    private fun showSuccessDialog(email: String) {

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Account Registered")
            .setMessage("Verification in process.\n\nOTP has been sent to your email.")
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ ->

                val intent = Intent(this, OtpVerificationActivity::class.java)
                intent.putExtra("EMAIL", email)
                startActivity(intent)
                finish()
            }
            .create()

        dialog.show()
    }
}