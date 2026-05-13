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

        val etUsername = findViewById<EditText>(R.id.etUsername)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        
        // Ensure boxes are inactive on start
        etUsername.clearFocus()
        etPassword.clearFocus()
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvRegister = findViewById<TextView>(R.id.tvRegister)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)

        val loginContainer = findViewById<View>(R.id.loginContainer)
        val mainContainerCard = findViewById<View>(R.id.mainContainerCard)

        setupInputField(R.id.usernameContainer, R.id.etUsername, R.id.iconUsername)
        setupInputField(R.id.passwordContainer, R.id.etPassword, R.id.iconPassword)

        // Premium split-screen entrance
        runEntranceAnimations(
            leftPane = mainContainerCard,
            formContainer = loginContainer,
            sequencedFields = listOf(
                R.id.loginHeader,
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
     * Premium entrance choreography for the split-screen layout:
     *
     *   1. Left illustration card slides in from the left (0 ms).
     *   2. Form container fades in from the right (160 ms).
     *   3. Form fields slide up in sequence (80 ms stagger).
     */
    private fun runEntranceAnimations(
        leftPane: View,
        formContainer: View,
        sequencedFields: List<Int>
    ) {
        // Left Pane slides in
        leftPane.alpha = 0f
        leftPane.translationX = -100f
        leftPane.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(1000L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.5f))
            .start()

        // Form Container fades in
        formContainer.alpha = 0f
        formContainer.translationX = 40f
        formContainer.animate()
            .alpha(1f)
            .translationX(0f)
            .setStartDelay(200L)
            .setDuration(800L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.0f))
            .start()

        // Each field fades and lifts up
        sequencedFields.forEachIndexed { index, viewId ->
            val view = findViewById<View>(viewId) ?: return@forEachIndexed
            view.alpha = 0f
            view.translationY = 30f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(400L + index * 80L)
                .setDuration(700L)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                .start()
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

            // 🔥 THIS IS WHAT TRIGGERS BORDER HIGHLIGHT
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