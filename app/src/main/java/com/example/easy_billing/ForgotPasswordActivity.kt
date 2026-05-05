package com.example.easy_billing

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.ForgotPasswordRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.applyPremiumClickAnimation
import com.example.easy_billing.util.runPremiumEntrance
import com.example.easy_billing.util.setupPremiumInputField
import com.example.easy_billing.util.startPremiumHeaderOscillation
import kotlinx.coroutines.launch

class ForgotPasswordActivity : BaseActivity() {

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        // Setup professional toolbar with back arrow
        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val btnSubmit = findViewById<Button>(R.id.btnSubmit)

        val otpLayout = findViewById<LinearLayout>(R.id.otpLayout)
        val etOtp = findViewById<EditText>(R.id.etOtp)
        val btnVerifyOtp = findViewById<Button>(R.id.btnVerifyOtp)
        val mainContent = findViewById<View>(R.id.mainContent)
        val wordmarkAccent = findViewById<View>(R.id.wordmarkAccent)

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
                findViewById(R.id.tvForgotBase),
                findViewById(R.id.tvForgotAccent),
                findViewById(R.id.tvTagline),
                findViewById(R.id.emailContainer),
                findViewById(R.id.btnSubmit)
            )
        )

        listOfNotNull(
            findViewById<TextView>(R.id.tvSecureLogin),
            findViewById<TextView>(R.id.tvAccountRecovery)
        )
            .startPremiumHeaderOscillation()

        setupPremiumInputField(
            findViewById(R.id.emailContainer),
            etEmail,
            findViewById(R.id.iconEmail)
        )

        setupPremiumInputField(
            findViewById(R.id.otpContainer),
            findViewById(R.id.etOtp),
            findViewById(R.id.iconOtp)
        )

        btnSubmit.applyPremiumClickAnimation()
        btnVerifyOtp.applyPremiumClickAnimation()

        btnSubmit.setOnClickListener {

            val email = etEmail.text.toString().trim()

            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter registered email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Enter a valid email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {

                try {

                    btnSubmit.isEnabled = false
                    btnSubmit.text = "Sending..."

                    val request = ForgotPasswordRequest(email)
                    val response = RetrofitClient.api.forgotPassword(request)

                    // Show OTP Layout
                    otpLayout.visibility = View.VISIBLE
                    etOtp.isEnabled = true
                    btnVerifyOtp.isEnabled = true
                    etEmail.isEnabled = false

                    Toast.makeText(
                        this@ForgotPasswordActivity,
                        response.message,
                        Toast.LENGTH_SHORT
                    ).show()

                    // Start 60 second countdown
                    object : CountDownTimer(60000, 1000) {

                        override fun onTick(millisUntilFinished: Long) {
                            val seconds = millisUntilFinished / 1000
                            btnSubmit.text = "Resend OTP in ${seconds} sec"
                            btnSubmit.isEnabled = false
                        }

                        override fun onFinish() {
                            btnSubmit.text = "Resend OTP"
                            btnSubmit.isEnabled = true
                        }

                    }.start()

                } catch (e: Exception) {

                    btnSubmit.isEnabled = true
                    btnSubmit.text = "Submit"

                    Toast.makeText(
                        this@ForgotPasswordActivity,
                        "Server error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        btnVerifyOtp.setOnClickListener {

            val otp = etOtp.text.toString().trim()
            val email = etEmail.text.toString().trim()

            if (otp.length != 6) {
                Toast.makeText(this, "Enter valid 6-digit OTP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {

                try {

                    btnVerifyOtp.isEnabled = false
                    btnVerifyOtp.text = "Verifying..."

                    val response = RetrofitClient.api.verifyOtp(email, otp)

                    if (response.isSuccessful) {

                        val body = response.body()

                        if (body?.otp_verified == true) {

                            val resetToken = body.access_token

                            // Save reset token
                            val prefs = getSharedPreferences("auth", MODE_PRIVATE)
                            prefs.edit()
                                .putString("TOKEN", resetToken)
                                .apply()

                            Log.d("RESET_TOKEN", "Saved token: $resetToken")

                            Toast.makeText(
                                this@ForgotPasswordActivity,
                                "OTP Verified Successfully",
                                Toast.LENGTH_SHORT
                            ).show()

                            val intent = Intent(
                                this@ForgotPasswordActivity,
                                ChangePasswordActivity::class.java
                            )

                            startActivity(intent)
                            finish()
                        }

                    } else {

                        val code = response.code()

                        if (response.code() == 401) {

                            Toast.makeText(
                                this@ForgotPasswordActivity,
                                "Invalid OTP",
                                Toast.LENGTH_SHORT
                            ).show()

                        } else if (response.code() == 429) {

                            Toast.makeText(
                                this@ForgotPasswordActivity,
                                "Too many attempts",
                                Toast.LENGTH_SHORT
                            ).show()

                        } else if (response.code() == 410) {

                            Toast.makeText(
                                this@ForgotPasswordActivity,
                                "OTP expired",
                                Toast.LENGTH_SHORT
                            ).show()
                        }  else {

                            Toast.makeText(
                                this@ForgotPasswordActivity,
                                "Server error (Code: ${response.code()})",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                } catch (e: Exception) {

                    Toast.makeText(
                        this@ForgotPasswordActivity,
                        "Network error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()

                } finally {

                    btnVerifyOtp.isEnabled = true
                    btnVerifyOtp.text = "Verify OTP"
                }
            }
        }
    }
}
