package com.example.easy_billing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.util.DeviceUtils
import com.example.easy_billing.util.PastelColor
import com.example.easy_billing.util.applyPremiumClickAnimation
import com.google.android.material.card.MaterialCardView
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.easy_billing.util.applyPremiumClickAnimation

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 🔥 GET FCM TOKEN (VERY IMPORTANT)
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val fcmToken = task.result

                    println("🔥 FCM TOKEN: $fcmToken")

                    // ✅ SAVE LOCALLY (optional but useful)
                    val prefs = getSharedPreferences("auth", MODE_PRIVATE)
                    prefs.edit { putString("FCM_TOKEN", fcmToken) }

                } else {
                    println("❌ FCM TOKEN FAILED")
                }
            }


        // 🔔 REQUEST NOTIFICATION PERMISSION (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1
                )
            }
        }

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        val tvWelcomeBase = findViewById<TextView>(R.id.tvWelcomeBase)
        val tvWelcomeAccent = findViewById<TextView>(R.id.tvWelcomeAccent)
        val wordmarkAccent = findViewById<View>(R.id.wordmarkAccent)
        val etUsername = findViewById<EditText>(R.id.etUsername)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvRegister = findViewById<TextView>(R.id.tvRegister)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)

        val mainContent = findViewById<View>(R.id.mainContent)

        setupInputField(R.id.usernameContainer, R.id.etUsername, R.id.iconUsername)
        setupInputField(R.id.passwordContainer, R.id.etPassword, R.id.iconPassword)

        // Trademark Elastic Green Line Animation
        wordmarkAccent.pivotX = 0f
        wordmarkAccent.scaleX = 0f
        wordmarkAccent.animate()
            .scaleX(1f)
            .setStartDelay(400L)
            .setDuration(1500L)
            .setInterpolator(android.view.animation.OvershootInterpolator(5f)) // Massive elastic snap
            .start()

        // Staggered premium entrance
        runEntranceAnimations(
            container = mainContent,
            sequencedFields = listOf(
                R.id.imgLogo, R.id.tvWelcomeBase, R.id.tvWelcomeAccent, R.id.tvTagline,
                R.id.usernameContainer, R.id.passwordContainer,
                R.id.tvForgotPassword, R.id.btnLogin, R.id.tvRegister
            )
        )

        // Register
        tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // Forgot password
        tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

        // Login
        btnLogin.applyPremiumClickAnimation()
        btnLogin.setOnClickListener {

            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val deviceId = DeviceUtils.getDeviceId(this)

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {

                try {

                    // ✅ INTERNET CHECK
                    if (!isInternetAvailable()) {
                        Toast.makeText(
                            this@MainActivity,
                            "No internet connection",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }

                    btnLogin.isEnabled = false   // prevent double click

                    val response = RetrofitClient.api.login(
                        username,
                        password,
                        deviceId   // ✅ ADD THIS
                    )
                    val token = response.access_token

                    if (token.isNullOrEmpty()) {
                        Toast.makeText(
                            this@MainActivity,
                            "Invalid login response",
                            Toast.LENGTH_LONG
                        ).show()
                        btnLogin.isEnabled = true
                        return@launch
                    }

                    // ✅ SAVE TOKEN
                    val prefs = getSharedPreferences("auth", MODE_PRIVATE)

                    prefs.edit {
                        putString("TOKEN", response.access_token)
                        putString("DEVICE_ID", deviceId)
                        putInt("SHOP_ID", response.shop_id)
                    }

                    lifecycleScope.launch(Dispatchers.IO) {
                        val syncManager = SyncManager(this@MainActivity)
                        syncManager.syncAll()
                    }

                    android.util.Log.d("TOKEN_DEBUG", "Saved Token: $token")

                    updateLastOnlineTime()
                    android.util.Log.d("SESSION", "Session initialized")

                    // NAVIGATION
                    val next = if (response.is_first_login) {
                        ChangePasswordActivity::class.java
                    } else {
                        DashboardActivity::class.java
                    }

                    startActivity(Intent(this@MainActivity, next))
                    finish()

                } catch (e: Exception) {

                    btnLogin.isEnabled = true

                    val message = if (e is retrofit2.HttpException) {
                        e.response()?.errorBody()?.string() ?: "Login failed"
                    } else {
                        e.message ?: "Unknown error"
                    }

                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Typewriter removed for luxury aesthetic

    /**
     * Premium entrance choreography:
     *
     *   1. Brand column slides in from the left (0 ms).
     *   2. Login card lifts + scales in (160 ms after).
     *   3. Each child of [sequencedFields] fades up in turn,
     *      80 ms apart, starting 360 ms after the card lands.
     *   4. The three decoration orbs start a slow infinite drift
     *      so the backdrop feels alive without being distracting.
     */
    private fun runEntranceAnimations(
        container: View,
        sequencedFields: List<Int>
    ) {
        // The container lifts slightly
        container.alpha = 0f
        container.translationY = 60f
        container.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(100L)
            .setDuration(800L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.5f))
            .start()

        // Each field fades, lifts, and scales up slightly for a luxurious feel
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

        // Production-grade detail: champagne shimmer pass on the brand wordmark
        startShimmerOnWelcome()
        startSecureLoginOscillation()
    }



    /**
     * Production-grade detail: slow champagne shimmer that drifts
     * across the brand wordmark every ~5 seconds. Achieved by
     * applying a horizontal `LinearGradient` shader to the text
     * paint and animating a `Matrix` translation along the text
     * width. Subtle enough to feel like a premium detail, not a
     * gimmick.
     */
    private fun startShimmerOnWelcome() {
        val tv = findViewById<TextView>(R.id.tvWelcomeAccent) ?: return
        tv.post {
            val width = tv.width.toFloat().takeIf { it > 0 } ?: return@post
            val baseColor = 0xFFD4A574.toInt()   // your gold color
            val highlight = 0xFFFFE6B0.toInt()   // brighter gold highlight
            val shader = android.graphics.LinearGradient(
                0f, 0f, width * 0.4f, 0f,
                intArrayOf(baseColor, highlight, baseColor),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            tv.paint.shader = shader

            val matrix = android.graphics.Matrix()
            val sweep = android.animation.ValueAnimator.ofFloat(-width, width * 1.4f).apply {
                duration = 2400L
                startDelay = 1200L
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.RESTART
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { va ->
                    matrix.setTranslate(va.animatedValue as Float, 0f)
                    shader.setLocalMatrix(matrix)
                    tv.invalidate()
                }
            }
            // Wait between sweeps: 3.5s gap.
            sweep.startDelay = 3500L
            sweep.start()
        }
    }

    private fun startSecureLoginOscillation() {
        val tv = findViewById<TextView>(R.id.tvSecureLogin) ?: return

        // Smooth alpha breathing
        val alphaAnim = android.animation.ObjectAnimator.ofFloat(tv, View.ALPHA, 0.5f, 1f).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
        }

        // Slight premium scale effect
        val scaleX = android.animation.ObjectAnimator.ofFloat(tv, View.SCALE_X, 0.98f, 1f).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
        }

        val scaleY = android.animation.ObjectAnimator.ofFloat(tv, View.SCALE_Y, 0.98f, 1f).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
        }

        // Optional subtle glow (matches your green accent line)
        val glowAnim = android.animation.ValueAnimator.ofFloat(0.3f, 1f).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE

            addUpdateListener {
                val alpha = it.animatedValue as Float
                tv.setShadowLayer(
                    10f * alpha,
                    0f,
                    0f,
                    android.graphics.Color.parseColor("#00FF41")
                )
            }
        }

        android.animation.AnimatorSet().apply {
            playTogether(alphaAnim, scaleX, scaleY, glowAnim)
            start()
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

        // tap anywhere → focus
        container.setOnClickListener {
            editText.requestFocus()
        }

        editText.setOnFocusChangeListener { _, hasFocus ->

            // 🔥 THIS IS WHAT TRIGGERS GREEN BORDER
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