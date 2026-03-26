package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import retrofit2.HttpException

class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_splash)

        // ✅ IMPORTANT: Initialize Retrofit context
        RetrofitClient.setContext(this)

        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val token = prefs.getString("TOKEN", null)

        lifecycleScope.launch {

            if (token.isNullOrEmpty()) {
                goToLogin()
                return@launch
            }

            val isValid = withContext(Dispatchers.IO) {
                try {
                    RetrofitClient.api.getProfile("Bearer $token")
                    true
                } catch (e: HttpException) {
                    e.code() != 401   // ❌ only logout if 401
                } catch (e: Exception) {
                    true  // 🔥 network issue → allow user
                }
            }

            if (isValid) {
                startActivity(Intent(this@SplashActivity, DashboardActivity::class.java))
            } else {
                prefs.edit { remove("TOKEN") }
                goToLogin()
            }

            finish()
        }
    }

    private fun goToLogin() {
        startActivity(Intent(this, MainActivity::class.java))
    }
}
