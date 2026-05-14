package com.example.easy_billing

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.ChangePasswordRequest
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch

class ChangePasswordActivity : BaseActivity() {

    override fun onCreate(animatedInstanceState: Bundle?) {
        super.onCreate(animatedInstanceState)
        setContentView(R.layout.activity_change_password)

        // Setup View References
        val etNewPassword = findViewById<EditText>(R.id.etNewPassword)
        val etConfirmPassword = findViewById<EditText>(R.id.etConfirmPassword)
        val btnChangePassword = findViewById<Button>(R.id.btnChangePassword)
        val btnBack = findViewById<View>(R.id.btnBack)
        val monolithCard = findViewById<View>(R.id.monolithCard)

        // 🔙 BACK BUTTON
        btnBack.setOnClickListener { finish() }

        // 🔥 MONOLITH ENTRANCE
        monolithCard.alpha = 0f
        monolithCard.scaleX = 0.95f
        monolithCard.scaleY = 0.95f
        monolithCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(900)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        // 🔥 CASCADING ENTRANCE: Form Elements
        val viewsToAnimate = listOf(
            findViewById<View>(R.id.headerIconCard),
            findViewById<View>(R.id.headerTitle),
            findViewById<View>(R.id.headerSubtitle),
            findViewById<View>(R.id.passwordSection)
        )
        
        viewsToAnimate.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationX = -30f
            view.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(800)
                .setStartDelay(400L + (index * 100L))
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }

        // ✨ INPUT FIELD SETUP
        setupInputField(R.id.passwordContainer, etNewPassword, findViewById(R.id.iconPassword))
        setupInputField(R.id.confirmPasswordContainer, etConfirmPassword, findViewById(R.id.iconConfirmPassword))

        btnChangePassword.applyPremiumClickAnimation()

        btnChangePassword.setOnClickListener {
            val newPass = etNewPassword.text.toString().trim()
            val confirmPass = etConfirmPassword.text.toString().trim()

            if (newPass.isEmpty() || confirmPass.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPass != confirmPass) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPass.length < 6) {
                Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    btnChangePassword.isEnabled = false
                    btnChangePassword.text = "Updating..."

                    // Retrieve reset token from SharedPreferences
                    val prefs = getSharedPreferences("auth", MODE_PRIVATE)
                    val token = prefs.getString("TOKEN", "") ?: ""

                    if (token.isEmpty()) {
                        Toast.makeText(this@ChangePasswordActivity, "Session expired. Please try again.", Toast.LENGTH_LONG).show()
                        finish()
                        return@launch
                    }

                    val response = RetrofitClient.api.resetPassword("Bearer $token", ChangePasswordRequest(newPass))
                    
                    if (response.isSuccessful) {
                        Toast.makeText(this@ChangePasswordActivity, "Password updated successfully!", Toast.LENGTH_LONG).show()
                        
                        // Navigate to Login Activity
                        val intent = Intent(this@ChangePasswordActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this@ChangePasswordActivity, "Failed to update password", Toast.LENGTH_SHORT).show()
                    }

                } catch (e: Exception) {
                    Toast.makeText(this@ChangePasswordActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    btnChangePassword.isEnabled = true
                    btnChangePassword.text = "Change Password"
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
                icon.setColorFilter(Color.parseColor("#6366F1"))
                editText.setHintTextColor(Color.parseColor("#6366F1"))
            } else {
                icon.setColorFilter(Color.parseColor("#94A3B8"))
                editText.setHintTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun View.applyPremiumClickAnimation() {
        this.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
            }
            false
        }
    }
}