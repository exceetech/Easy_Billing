package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch
import androidx.core.content.edit

class ChangePasswordActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_change_password)

        val etNewPassword = findViewById<EditText>(R.id.etNewPassword)
        val btnSave = findViewById<Button>(R.id.btnSavePassword)

        btnSave.setOnClickListener {

            val newPass = etNewPassword.text.toString().trim()

            if (newPass.length < 4) {
                Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            if (token == null) {
                Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    val response = RetrofitClient.api.changePassword(
                        "Bearer $token",
                        newPass
                    )

                    if (response.isSuccessful) {

                        Toast.makeText(
                            this@ChangePasswordActivity,
                            "Password changed. Please login again.",
                            Toast.LENGTH_LONG
                        ).show()

                        getSharedPreferences("auth", MODE_PRIVATE)
                            .edit {
                                remove("TOKEN")
                            }

                        startActivity(Intent(this@ChangePasswordActivity, MainActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this@ChangePasswordActivity, "Failed", Toast.LENGTH_SHORT).show()
                    }

                } catch (e: Exception) {
                    Toast.makeText(this@ChangePasswordActivity, "Error occurred", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}