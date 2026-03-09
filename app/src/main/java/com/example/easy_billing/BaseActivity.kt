package com.example.easy_billing

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.VerifyPasswordRequest
import kotlinx.coroutines.launch

open class BaseActivity : AppCompatActivity() {

    protected fun setupToolbar(toolbarId: Int, showBack: Boolean = true) {

        val toolbar = findViewById<Toolbar>(toolbarId)
        setSupportActionBar(toolbar)

        if (showBack) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setDisplayShowHomeEnabled(true)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    protected fun verifyPassword(
        password: String,
        onSuccess: () -> Unit
    ) {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val response = RetrofitClient.api.verifyPassword(
                    "Bearer $token",
                    VerifyPasswordRequest(password)
                )

                if (response.isSuccessful) {

                    onSuccess()

                } else {

                    Toast.makeText(
                        this@BaseActivity,
                        "Incorrect password",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {

                Toast.makeText(
                    this@BaseActivity,
                    "Verification failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}