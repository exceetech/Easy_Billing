package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch
import androidx.core.content.edit

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        if (token != null) {
            lifecycleScope.launch {
                try {
                    RetrofitClient.api.getProfile("Bearer $token")

                    startActivity(Intent(this@SplashActivity, DashboardActivity::class.java))
                    finish()

                } catch (e: Exception) {

                    getSharedPreferences("auth", MODE_PRIVATE)
                        .edit {
                            remove("TOKEN")
                        }

                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    finish()
                }
            }
        } else {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}