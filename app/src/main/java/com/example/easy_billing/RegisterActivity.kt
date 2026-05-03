package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RegisterRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.applyPremiumClickAnimation
import kotlinx.coroutines.launch

class RegisterActivity : BaseActivity() {

    private var isRegistering = false   // 🔥 FLAG TO PREVENT DOUBLE CLICK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = ""

        val etFullName = findViewById<EditText>(R.id.etFullName)
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPhoneNumber = findViewById<EditText>(R.id.etPhoneNumber)
        val etShopName = findViewById<EditText>(R.id.ShopName)
        val btnRegister = findViewById<Button>(R.id.btnRegister)
        val tvBackToLogin = findViewById<TextView>(R.id.tvBackToLogin)

        val mainContent = findViewById<View>(R.id.mainContent)
        val wordmarkAccent = findViewById<View>(R.id.wordmarkAccent)

        setupInputField(R.id.nameContainer, R.id.etFullName, R.id.iconFullName)
        setupInputField(R.id.emailContainer, R.id.etEmail, R.id.iconEmail)
        setupInputField(R.id.phoneContainer, R.id.etPhoneNumber, R.id.iconPhone)
        setupInputField(R.id.shopContainer, R.id.ShopName, R.id.iconShop)

        // Trademark Elastic Green Line Animation
        wordmarkAccent.pivotX = 0f
        wordmarkAccent.scaleX = 0f
        wordmarkAccent.animate()
            .scaleX(1f)
            .setStartDelay(400L)
            .setDuration(1500L)
            .setInterpolator(android.view.animation.OvershootInterpolator(5f)) // Massive elastic snap
            .start()

        runRegisterEntranceAnimations(
            container = mainContent,
            sequencedFields = listOf(
                R.id.imgLogo, R.id.tvRegisterBase, R.id.tvRegisterAccent, R.id.tvTagline,
                R.id.nameContainer, R.id.emailContainer,
                R.id.phoneContainer, R.id.shopContainer,
                R.id.btnRegister, R.id.tvBackToLogin
            )
        )

        startHeaderOscillation()

        btnRegister.applyPremiumClickAnimation()

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
        button.text = "Create Account"
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

    /**
     * Mirrors [MainActivity.runEntranceAnimations] so the visual
     * choreography across login + register feels like one product.
     *
     *   1. Brand column slides in (0 ms).
     *   2. Card lifts + scales in (160 ms).
     *   3. Each child of [sequencedFields] cascades in 80 ms apart
     *      starting at 360 ms.
     */
    private fun runRegisterEntranceAnimations(
        container: View,
        sequencedFields: List<Int>
    ) {
        container.alpha = 0f
        container.translationY = 60f
        container.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(100L)
            .setDuration(800L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.5f))
            .start()

        sequencedFields.forEachIndexed { index, viewId ->
            val view = findViewById<View>(viewId) ?: return@forEachIndexed
            view.alpha = 0f
            view.translationY = 30f
            view.scaleX = 0.95f
            view.scaleY = 0.95f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(250L + index * 50L)
                .setDuration(700L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.0f))
                .start()
        }
    }


    private fun startHeaderOscillation() {

        val headers = listOf(
            findViewById<TextView>(R.id.tvSecureLogin),
            findViewById<TextView>(R.id.tvNewAccount)
        ).filterNotNull()

        headers.forEach { tv ->

            // 🔹 smooth breathing (opacity)
            val alphaAnim = android.animation.ObjectAnimator.ofFloat(tv, View.ALPHA, 0.6f, 1f).apply {
                duration = 1400
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
            }

            // 🔹 subtle premium scale
            val scaleX = android.animation.ObjectAnimator.ofFloat(tv, View.SCALE_X, 0.98f, 1f).apply {
                duration = 1400
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
            }

            val scaleY = android.animation.ObjectAnimator.ofFloat(tv, View.SCALE_Y, 0.98f, 1f).apply {
                duration = 1400
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
            }

            // 🔹 micro horizontal drift (VERY subtle, not cheap)
            val translateX = android.animation.ObjectAnimator.ofFloat(tv, View.TRANSLATION_X, 0f, 4f).apply {
                duration = 1400
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
            }

            // 🔹 soft glow (luxury feel)
            val glowAnim = android.animation.ValueAnimator.ofFloat(0.2f, 1f).apply {
                duration = 1400
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE

                addUpdateListener {
                    val value = it.animatedValue as Float
                    tv.setShadowLayer(
                        8f * value,
                        0f,
                        0f,
                        android.graphics.Color.parseColor("#00FF41")
                    )
                }
            }

            // 🔹 combine everything
            android.animation.AnimatorSet().apply {
                playTogether(alphaAnim, scaleX, scaleY, translateX, glowAnim)
                start()
            }
        }
    }

    private fun setupInputField(
        containerId: Int,
        editTextId: Int,
        iconId: Int
    ) {
        val container = findViewById<View>(containerId)
        val editText = findViewById<EditText>(editTextId)
        val icon = findViewById<ImageView>(iconId)

        container.setOnClickListener {
            editText.requestFocus()
        }

        editText.setOnFocusChangeListener { _, hasFocus ->

            // 🔥 THIS LINE WAS MISSING (MAIN BUG)
            container.isActivated = hasFocus

            if (hasFocus) {
                icon.setColorFilter(android.graphics.Color.parseColor("#00C853"))
                editText.setHintTextColor(android.graphics.Color.parseColor("#00C853"))
            } else {
                icon.setColorFilter(android.graphics.Color.parseColor("#8F9098"))
                editText.setHintTextColor(android.graphics.Color.parseColor("#71717A"))
            }
        }
    }

}