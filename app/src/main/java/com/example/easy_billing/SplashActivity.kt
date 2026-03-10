package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // show splash UI
        setContentView(R.layout.activity_splash)

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        lifecycleScope.launch {

            if (token != null) {

                val isValid = withContext(Dispatchers.IO) {
                    try {
                        RetrofitClient.api.getProfile("Bearer $token")
                        true
                    } catch (e: Exception) {
                        false
                    }
                }

                if (isValid) {

                    startActivity(
                        Intent(this@SplashActivity, DashboardActivity::class.java)
                    )

                } else {

                    getSharedPreferences("auth", MODE_PRIVATE)
                        .edit {
                            remove("TOKEN")
                        }

                    startActivity(
                        Intent(this@SplashActivity, MainActivity::class.java)
                    )
                }

            } else {

                startActivity(
                    Intent(this@SplashActivity, MainActivity::class.java)
                )
            }

            finish()
        }
    }
}