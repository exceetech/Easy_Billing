package com.example.easy_billing

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
        // Setup View References
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etOtp = findViewById<EditText>(R.id.etOtp)
        val btnSubmit = findViewById<Button>(R.id.btnSubmit)
        val btnVerifyOtp = findViewById<Button>(R.id.btnVerifyOtp)
        val otpLayout = findViewById<LinearLayout>(R.id.otpLayout)
        val btnBack = findViewById<View>(R.id.btnBack)
        val monolithCard = findViewById<View>(R.id.monolithCard)

        // 🔙 BACK BUTTON
        btnBack.setOnClickListener { finish() }

        // 🔥 MONOLITH ENTRANCE
        monolithCard.alpha = 0f
        monolithCard.scaleX = 0.95f
        monolithCard.scaleY = 0.95f
        monolithCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(900)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .start()

        // 🔥 CASCADING ENTRANCE: Recovery Form (Initially visible elements only)
        val viewsToAnimate = listOf(
            findViewById<View>(R.id.headerIconCard),
            findViewById<View>(R.id.headerTitle),
            findViewById<View>(R.id.headerSubtitle),
            findViewById<View>(R.id.emailSection)
        )
        
        viewsToAnimate.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationX = -30f
            view.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(800)
                .setStartDelay(400L + (index * 100L))
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .start()
        }

        // ✨ INPUT FIELD SETUP
        setupInputField(R.id.emailContainer, etEmail, findViewById(R.id.iconEmail))
        setupInputField(R.id.otpContainer, etOtp, findViewById(R.id.iconOtp))

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

                    // 🚀 CINEMATIC REVEAL: Show OTP Layout
                    if (otpLayout.visibility == View.GONE) {
                        otpLayout.visibility = View.VISIBLE
                        otpLayout.alpha = 0f
                        otpLayout.translationY = 20f
                        otpLayout.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(600)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                            .start()
                    }

                    etOtp.isEnabled = true
                    btnVerifyOtp.isEnabled = true
                    etEmail.isEnabled = false

                    Toast.makeText(this@ForgotPasswordActivity, response.message, Toast.LENGTH_SHORT).show()

                    // Start 60 second countdown
                    object : CountDownTimer(60000, 1000) {
                        override fun onTick(millisUntilFinished: Long) {
                            val seconds = millisUntilFinished / 1000
                            btnSubmit.text = "Resend in ${seconds}s"
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
                    Toast.makeText(this@ForgotPasswordActivity, "Server error: ${e.message}", Toast.LENGTH_SHORT).show()
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
                            getSharedPreferences("auth", MODE_PRIVATE).edit()
                                .putString("TOKEN", resetToken)
                                .apply()

                            Toast.makeText(this@ForgotPasswordActivity, "OTP Verified Successfully", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this@ForgotPasswordActivity, ChangePasswordActivity::class.java))
                            finish()
                        }
                    } else {
                        when (response.code()) {
                            401 -> Toast.makeText(this@ForgotPasswordActivity, "Invalid OTP", Toast.LENGTH_SHORT).show()
                            429 -> Toast.makeText(this@ForgotPasswordActivity, "Too many attempts", Toast.LENGTH_SHORT).show()
                            410 -> Toast.makeText(this@ForgotPasswordActivity, "OTP expired", Toast.LENGTH_SHORT).show()
                            else -> Toast.makeText(this@ForgotPasswordActivity, "Server error: ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@ForgotPasswordActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    btnVerifyOtp.isEnabled = true
                    btnVerifyOtp.text = "Verify OTP"
                }
            }
        }
    }

    private fun setupInputField(containerId: Int, editText: EditText, icon: ImageView) {
        val container = findViewById<View>(containerId)
        
        container.setOnClickListener { editText.requestFocus() }
        
        editText.setOnFocusChangeListener { _, hasFocus ->
            container.isActivated = hasFocus
            if (hasFocus) {
                icon.setColorFilter(android.graphics.Color.parseColor("#6366F1"))
                editText.setHintTextColor(android.graphics.Color.parseColor("#6366F1"))
            } else {
                icon.setColorFilter(android.graphics.Color.parseColor("#94A3B8"))
                editText.setHintTextColor(android.graphics.Color.parseColor("#94A3B8"))
            }
        }
    }
}
