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
        com.example.easy_billing.util.UserEventLogger.logAction("ForgotPassword", "opened")
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
                Toast.makeText(this, R.string.please_enter_registered_email, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, R.string.enter_a_valid_email, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    btnSubmit.isEnabled = false
                    btnSubmit.text = getString(R.string.sending_ellipsis)
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
                            btnSubmit.text = getString(R.string.resend_otp)
                            btnSubmit.isEnabled = true
                            startCtaArrowAnimation(R.id.btnSubmit)
                        }
                    }.start()

                } catch (e: Exception) {
                    btnSubmit.isEnabled = true
                    btnSubmit.text = getString(R.string.send_reset_code)
                    startCtaArrowAnimation(R.id.btnSubmit)
                    // Was surfacing the raw exception message to the user.
                    Log.e("ForgotPasswordActivity", "Send reset code failed", e)
                    com.example.easy_billing.util.UserEventLogger.logError(
                        "ForgotPasswordActivity", "send_reset_code_failed: ${e.javaClass.simpleName}"
                    )
                    Toast.makeText(this@ForgotPasswordActivity, R.string.something_went_wrong, Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnVerifyOtp.setOnClickListener {
            val otp = etOtp.text.toString().trim()
            val email = etEmail.text.toString().trim()

            if (otp.length != 6) {
                Toast.makeText(this, R.string.enter_valid_6_digit_otp, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    btnVerifyOtp.isEnabled = false
                    btnVerifyOtp.text = getString(R.string.verifying_ellipsis)
                    hideCtaArrow(R.id.btnVerifyOtp)

                    val response = RetrofitClient.api.verifyOtp(email, otp)
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body?.otp_verified == true) {
                            val resetToken = body.access_token
                            getSharedPreferences("auth", MODE_PRIVATE).edit()
                                .putString("RESET_TOKEN", resetToken)
                                .apply()

                            Toast.makeText(this@ForgotPasswordActivity, R.string.otp_verified_successfully, Toast.LENGTH_SHORT).show()
                            // Was navigating away in the same instant as
                            // showing the toast — ChangePasswordActivity
                            // covers this screen before the toast has any
                            // real chance to be read. A short delay lets it
                            // actually register before we move on.
                            android.os.Handler(mainLooper).postDelayed({
                                startActivity(Intent(this@ForgotPasswordActivity, ChangePasswordActivity::class.java))
                                finish()
                            }, 600)
                        }
                    } else {
                        when (response.code()) {
                            401 -> Toast.makeText(this@ForgotPasswordActivity, R.string.invalid_otp, Toast.LENGTH_SHORT).show()
                            429 -> Toast.makeText(this@ForgotPasswordActivity, R.string.too_many_attempts, Toast.LENGTH_SHORT).show()
                            410 -> Toast.makeText(this@ForgotPasswordActivity, R.string.otp_expired, Toast.LENGTH_SHORT).show()
                            else -> Toast.makeText(this@ForgotPasswordActivity, "Server error: ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ForgotPasswordActivity", "Verify OTP failed", e)
                    com.example.easy_billing.util.UserEventLogger.logError(
                        "ForgotPasswordActivity", "verify_otp_failed: ${e.javaClass.simpleName}"
                    )
                    Toast.makeText(this@ForgotPasswordActivity, R.string.something_went_wrong, Toast.LENGTH_SHORT).show()
                } finally {
                    btnVerifyOtp.isEnabled = true
                    btnVerifyOtp.text = getString(R.string.verify_code)
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
