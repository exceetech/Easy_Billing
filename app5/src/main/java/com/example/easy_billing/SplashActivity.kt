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

        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val token = prefs.getString("TOKEN", null)

        lifecycleScope.launch {

            if (token.isNullOrEmpty()) {
                goToLogin()
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                try {
                    RetrofitClient.api.getProfile(token)
                    SplashResult.VALID
                } catch (e: HttpException) {
                    when (e.code()) {
                        401  -> SplashResult.INVALID_TOKEN
                        409  -> SplashResult.WORKSPACE_CHANGED
                        else -> SplashResult.VALID   // network/server hiccup → let through
                    }
                } catch (e: Exception) {
                    SplashResult.VALID   // offline → let user into cached Dashboard
                }
            }

            when (result) {
                SplashResult.VALID -> {
                    startActivity(Intent(this@SplashActivity, DashboardActivity::class.java))
                    finish()
                }
                SplashResult.INVALID_TOKEN -> {
                    prefs.edit { remove("TOKEN") }
                    goToLogin()
                    finish()
                }
                SplashResult.WORKSPACE_CHANGED -> {
                    // WorkspaceInterceptor also handles this at the OkHttp level,
                    // but if it fires here during splash we redirect explicitly.
                    val intent = Intent(this@SplashActivity, WorkspaceChangedActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    private fun goToLogin() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private enum class SplashResult { VALID, INVALID_TOKEN, WORKSPACE_CHANGED }
}
