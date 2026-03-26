package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.ForgotPasswordRequest
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch
import androidx.core.content.edit

class OtpVerificationActivity : BaseActivity() {

    private lateinit var etOtp: EditText
    private lateinit var btnVerify: Button
    private lateinit var tvResendOtp: TextView

    private var email: String? = null
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp_verification)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        etOtp = findViewById(R.id.etOtp)
        btnVerify = findViewById(R.id.btnVerify)
        tvResendOtp = findViewById(R.id.tvResendOtp)

        email = intent.getStringExtra("EMAIL")

        if (email.isNullOrEmpty()) {
            Toast.makeText(this, "Something went wrong", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 🔥 START TIMER IMMEDIATELY (OTP already sent during register)
        startResendTimer()

        // ================= RESEND OTP =================
        tvResendOtp.setOnClickListener {

            lifecycleScope.launch {
                try {

                    tvResendOtp.isEnabled = false
                    tvResendOtp.text = "Sending..."

                    RetrofitClient.api.forgotPassword(
                        ForgotPasswordRequest(email!!)
                    )

                    Toast.makeText(this@OtpVerificationActivity, "OTP sent", Toast.LENGTH_SHORT).show()

                    startResendTimer()

                } catch (e: Exception) {

                    tvResendOtp.isEnabled = true
                    tvResendOtp.text = "Resend OTP"

                    Toast.makeText(
                        this@OtpVerificationActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // ================= VERIFY OTP =================
        btnVerify.setOnClickListener {

            val otp = etOtp.text.toString().trim()

            if (otp.length != 6) {
                Toast.makeText(this, "Enter valid OTP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {

                    btnVerify.isEnabled = false
                    btnVerify.text = "Verifying..."

                    val response = RetrofitClient.api.verifyOtp(email!!, otp)

                    if (response.isSuccessful) {

                        val body = response.body()

                        if (body?.otp_verified == true) {

                            val token = body.access_token

                            getSharedPreferences("auth", MODE_PRIVATE)
                                .edit {
                                    putString("TOKEN", token)
                                }

                            Toast.makeText(this@OtpVerificationActivity, "OTP Verified", Toast.LENGTH_SHORT).show()

                            startActivity(
                                Intent(this@OtpVerificationActivity, ChangePasswordActivity::class.java)
                            )
                            finish()
                        }

                    } else {

                        when (response.code()) {
                            401 -> Toast.makeText(this@OtpVerificationActivity, "Invalid OTP", Toast.LENGTH_SHORT).show()
                            410 -> Toast.makeText(this@OtpVerificationActivity, "OTP expired", Toast.LENGTH_SHORT).show()
                            429 -> Toast.makeText(this@OtpVerificationActivity, "Too many attempts", Toast.LENGTH_SHORT).show()
                            else -> Toast.makeText(this@OtpVerificationActivity, "Error ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                    }

                } catch (e: Exception) {

                    Toast.makeText(
                        this@OtpVerificationActivity,
                        "Network error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()

                } finally {

                    btnVerify.isEnabled = true
                    btnVerify.text = "Verify OTP"
                }
            }
        }
    }

    // ================= TIMER =================
    private fun startResendTimer() {

        timer?.cancel()

        timer = object : CountDownTimer(60000, 1000) {

            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                tvResendOtp.text = "Resend OTP in ${seconds}s"
                tvResendOtp.isEnabled = false
            }

            override fun onFinish() {
                tvResendOtp.text = "Resend OTP"
                tvResendOtp.isEnabled = true
            }

        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
}