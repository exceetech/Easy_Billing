package com.example.easy_billing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.google.android.material.card.MaterialCardView
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        RetrofitClient.setContext(this)

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

        val tvWelcome = findViewById<TextView>(R.id.tvWelcome)
        val etUsername = findViewById<EditText>(R.id.etUsername)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvRegister = findViewById<TextView>(R.id.tvRegister)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)

        val loginCard = findViewById<MaterialCardView>(R.id.loginCard)
        val usernameCard = findViewById<MaterialCardView>(R.id.usernameCard)
        val passwordCard = findViewById<MaterialCardView>(R.id.passwordCard)

        // UI
        typeWriter(tvWelcome, "Welcome to ExPOS")

        loginCard.setCardBackgroundColor(PastelColor.random())
        usernameCard.setCardBackgroundColor(PastelColor.random())
        passwordCard.setCardBackgroundColor(PastelColor.random())
        btnLogin.setBackgroundColor(PastelColor.random())

        // Register
        tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // Forgot password
        tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

        // Login
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

                    val response = RetrofitClient.api.login(username, password)
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
                        putString("TOKEN", token)
                        putString("DEVICE_ID", deviceId)
                    }

                    lifecycleScope.launch(Dispatchers.IO) {
                        val syncManager = SyncManager(this@MainActivity)
                        syncManager.syncBills()
                    }

                    android.util.Log.d("TOKEN_DEBUG", "Saved Token: $token")

                    // ✅ START SESSION
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

    // ---------------- TYPEWRITER ----------------

    private fun typeWriter(textView: TextView, message: String, delay: Long = 40) {

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var index = 0

        handler.post(object : Runnable {
            override fun run() {
                if (index <= message.length) {
                    textView.text = message.substring(0, index)
                    index++
                    handler.postDelayed(this, delay)
                }
            }
        })
    }
}