package com.example.easy_billing

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
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

    private lateinit var otpBoxes: List<EditText>
    private lateinit var otpTarget: EditText
    private var updatingOtp = false

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
        val monolithCard = findViewById<View>(R.id.monolithCard)

        // 🔙 TOOLBAR + BACK (same as Inventory)
        setupToolbar(R.id.toolbar)

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

        // 6-box OTP wiring (syncs into the hidden etOtp field)
        otpTarget = etOtp
        otpBoxes = listOf(
            findViewById(R.id.otpBox1),
            findViewById(R.id.otpBox2),
            findViewById(R.id.otpBox3),
            findViewById(R.id.otpBox4),
            findViewById(R.id.otpBox5),
            findViewById(R.id.otpBox6)
        )
        setupOtpBoxes()

        btnSubmit.applyPremiumClickAnimation()
        btnVerifyOtp.applyPremiumClickAnimation()
        startCtaArrowAnimation(R.id.btnSubmit)
        startCtaArrowAnimation(R.id.btnVerifyOtp)

        // ⬅️ FOOTER: BACK TO LOGIN
        findViewById<TextView>(R.id.tvBackToLogin).setOnClickListener { finish() }

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
                    hideCtaArrow(R.id.btnSubmit)

                    val request = ForgotPasswordRequest(email)
                    val response = RetrofitClient.api.forgotPassword(request)

                    findViewById<TextView>(R.id.tvOtpSentTo).text =
                        "Enter the 6-digit code sent to $email"

                    // 🚀 CINEMATIC REVEAL: expand OTP section inline (same page)
                    if (otpLayout.visibility == View.GONE) {
                        otpLayout.visibility = View.VISIBLE
                        otpLayout.alpha = 0f
                        otpLayout.translationY = 24f
                        otpLayout.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setStartDelay(120)
                            .setDuration(560)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .withEndAction { otpBoxes.firstOrNull()?.requestFocus() }
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
                            startCtaArrowAnimation(R.id.btnSubmit)
                        }
                    }.start()

                } catch (e: Exception) {
                    btnSubmit.isEnabled = true
                    btnSubmit.text = "Send reset code"
                    startCtaArrowAnimation(R.id.btnSubmit)
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
                    hideCtaArrow(R.id.btnVerifyOtp)

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
                    btnVerifyOtp.text = "Verify code"
                    startCtaArrowAnimation(R.id.btnVerifyOtp)
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
                icon.setColorFilter(android.graphics.Color.parseColor("#0F6E56"))
                editText.setHintTextColor(android.graphics.Color.parseColor("#A99E88"))
            } else {
                icon.setColorFilter(android.graphics.Color.parseColor("#B8895A"))
                editText.setHintTextColor(android.graphics.Color.parseColor("#A99E88"))
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
        otpTarget.setText(otpBoxes.joinToString("") { it.text.toString() })
    }

    /** Show the arrow icon and loop its motion. */
    private fun startCtaArrowAnimation(buttonId: Int) {
        val btn = findViewById<com.google.android.material.button.MaterialButton>(buttonId)
        btn.icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_cta_arrow)
        btn.post { (btn.icon as? android.graphics.drawable.Animatable)?.start() }
    }

    /** Hide the arrow icon (used while the button shows a loading label). */
    private fun hideCtaArrow(buttonId: Int) {
        findViewById<com.google.android.material.button.MaterialButton>(buttonId).icon = null
    }
}
