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
import com.example.easy_billing.util.applyPremiumClickAnimation
import kotlinx.coroutines.launch
import androidx.core.content.edit
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.example.easy_billing.util.runPremiumEntrance
import com.example.easy_billing.util.setupPremiumInputField
import com.example.easy_billing.util.startPremiumHeaderOscillation

class ChangePasswordActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_change_password)

        val etNewPassword = findViewById<EditText>(R.id.etNewPassword)
        val etConfirmPassword = findViewById<EditText>(R.id.etConfirmPassword)
        val btnSave = findViewById<Button>(R.id.btnSavePassword)

        val mainContent = findViewById<View>(R.id.mainContent)
        val wordmarkAccent = findViewById<View>(R.id.wordmarkAccent)

        val iconNewPassword = findViewById<ImageView>(R.id.iconNewPassword)
        val iconConfirmPassword = findViewById<ImageView>(R.id.iconConfirmPassword)

        // Trademark Elastic Green Line Animation
        wordmarkAccent.pivotX = 0f
        wordmarkAccent.scaleX = 0f
        wordmarkAccent.animate()
            .scaleX(1f)
            .setStartDelay(400L)
            .setDuration(1500L)
            .setInterpolator(android.view.animation.OvershootInterpolator(5f))
            .start()


        mainContent.runPremiumEntrance(
            listOf(
                findViewById(R.id.imgLogo),
                findViewById(R.id.tvChangeBase),
                findViewById(R.id.tvChangeAccent),
                findViewById(R.id.tvTagline),
                findViewById(R.id.newPasswordContainer),
                findViewById(R.id.confirmPasswordContainer),
                findViewById(R.id.btnSavePassword)
            )
        )

        setupPremiumInputField(
            findViewById(R.id.newPasswordContainer),
            etNewPassword,
            iconNewPassword
        )

        setupPremiumInputField(
            findViewById(R.id.confirmPasswordContainer),
            etConfirmPassword,
            iconConfirmPassword
        )

        listOfNotNull(
            findViewById<TextView>(R.id.tvSecurityUpdate)
        )
            .startPremiumHeaderOscillation()

        btnSave.applyPremiumClickAnimation()

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
                    prefs.edit { remove("TOKEN") }

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