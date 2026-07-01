package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
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
    private lateinit var otpBoxes: List<EditText>

    private var email: String? = null
    private var timer: CountDownTimer? = null
    private var updatingOtp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp_verification)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        etOtp = findViewById(R.id.etOtp)
        btnVerify = findViewById(R.id.btnVerify)
        tvResendOtp = findViewById(R.id.tvResendOtp)
        otpBoxes = listOf(
            findViewById(R.id.otpBox1),
            findViewById(R.id.otpBox2),
            findViewById(R.id.otpBox3),
            findViewById(R.id.otpBox4),
            findViewById(R.id.otpBox5),
            findViewById(R.id.otpBox6)
        )

        email = intent.getStringExtra("EMAIL")

        if (email.isNullOrEmpty()) {
            Toast.makeText(this, "Something went wrong", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.tvOtpEmail).text = email
        setupOtpBoxes()
        startCtaArrowAnimation()

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
                    hideCtaArrow()

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
                    btnVerify.text = "Verify code"
                    startCtaArrowAnimation()
                }
            }
        }
    }

    // ================= 6-BOX OTP INPUT =================
    private fun setupOtpBoxes() {
        otpBoxes.forEachIndexed { index, box ->

            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (updatingOtp) return
                    val text = s?.toString().orEmpty()

                    // Paste / multi-char: spread across boxes from here.
                    if (text.length > 1) {
                        distributeOtp(text, index)
                        return
                    }

                    if (text.length == 1 && index < otpBoxes.lastIndex) {
                        otpBoxes[index + 1].requestFocus()
                    }
                    syncOtp()
                }
            })

            // Backspace on an empty box jumps to the previous one.
            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    box.text.isEmpty() && index > 0
                ) {
                    val prev = otpBoxes[index - 1]
                    prev.requestFocus()
                    prev.setText("")
                    syncOtp()
                    return@setOnKeyListener true
                }
                false
            }
        }
    }

    private fun distributeOtp(text: String, startIndex: Int) {
        val digits = text.filter { it.isDigit() }
        updatingOtp = true
        var i = startIndex
        for (ch in digits) {
            if (i > otpBoxes.lastIndex) break
            otpBoxes[i].setText(ch.toString())
            i++
        }
        updatingOtp = false

        val focusIndex = minOf(i, otpBoxes.lastIndex)
        otpBoxes[focusIndex].requestFocus()
        otpBoxes[focusIndex].setSelection(otpBoxes[focusIndex].text.length)
        syncOtp()
    }

    private fun syncOtp() {
        etOtp.setText(otpBoxes.joinToString("") { it.text.toString() })
    }

    /** Show the arrow icon and loop its motion. */
    private fun startCtaArrowAnimation() {
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnVerify)
        btn.icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_cta_arrow)
        btn.post {
            (btn.icon as? android.graphics.drawable.Animatable)?.start()
        }
    }

    /** Hide the arrow icon (used while the button shows a loading label). */
    private fun hideCtaArrow() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnVerify).icon = null
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