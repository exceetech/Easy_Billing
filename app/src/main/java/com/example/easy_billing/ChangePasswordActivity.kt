package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.ChangePasswordRequest
import kotlinx.coroutines.launch

class ChangePasswordActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_change_password)

        val etNewPassword = findViewById<EditText>(R.id.etNewPassword)
        val etConfirmPassword = findViewById<EditText>(R.id.etConfirmPassword)
        val btnSave = findViewById<Button>(R.id.btnSavePassword)

        btnSave.setOnClickListener {

            val newPass = etNewPassword.text.toString().trim()
            val confirmPass = etConfirmPassword.text.toString().trim()

            if (newPass.length < 4) {
                etNewPassword.error = "Password must be at least 4 characters"
                return@setOnClickListener
            }

            if (newPass != confirmPass) {
                etConfirmPassword.error = "Passwords do not match"
                return@setOnClickListener
            }

            val prefs = getSharedPreferences("auth", MODE_PRIVATE)

            // reset token saved after OTP verification
            val token = prefs.getString("TOKEN", null)

            Log.d("RESET_TOKEN", "Token = $token")

            if (token.isNullOrEmpty()) {

                Toast.makeText(
                    this,
                    "Reset session expired. Please request OTP again.",
                    Toast.LENGTH_SHORT
                ).show()

                startActivity(Intent(this, ForgotPasswordActivity::class.java))
                finish()
                return@setOnClickListener
            }

            lifecycleScope.launch {

                try {

                    val response = RetrofitClient.api.resetPassword(
                        "Bearer $token",
                        ChangePasswordRequest(newPass)
                    )

                    Toast.makeText(
                        this@ChangePasswordActivity,
                        "Password changed successfully",
                        Toast.LENGTH_LONG
                    ).show()

                    // clear reset token
                    prefs.edit().remove("TOKEN").apply()

                    val intent = Intent(
                        this@ChangePasswordActivity,
                        MainActivity::class.java
                    )

                    intent.flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

                    startActivity(intent)
                    finish()

                } catch (e: Exception) {

                    Log.e("RESET_PASSWORD_ERROR", e.message ?: "Unknown")

                    Toast.makeText(
                        this@ChangePasswordActivity,
                        "Unable to reset password",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}