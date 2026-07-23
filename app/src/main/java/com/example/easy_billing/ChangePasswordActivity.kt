package com.example.easy_billing

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
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
        val monolithCard = findViewById<View>(R.id.monolithCard)

        // 🔙 TOOLBAR + BACK (same as Inventory)
        setupToolbar(R.id.toolbar)

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

        // 👁 PASSWORD VISIBILITY TOGGLES
        setupPasswordToggle(etNewPassword, findViewById(R.id.btnToggleNewPass))
        setupPasswordToggle(etConfirmPassword, findViewById(R.id.btnToggleConfirmPass))

        // 💪 STRENGTH METER + ✅ MATCH CHECK
        etNewPassword.addTextChangedListener(simpleWatcher {
            updateStrengthMeter(etNewPassword.text.toString())
            updateMatchState(etNewPassword.text.toString(), etConfirmPassword.text.toString())
        })
        etConfirmPassword.addTextChangedListener(simpleWatcher {
            updateMatchState(etNewPassword.text.toString(), etConfirmPassword.text.toString())
        })

        // ⬅️ FOOTER: BACK TO LOGIN
        findViewById<TextView>(R.id.tvBackToLogin).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        startCtaArrowAnimation()
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
                    hideCtaArrow()

                    // This screen is reached from two different flows that
                    // must NOT be treated the same (Report 5 fix):
                    //   1. Forgot-password (OtpVerificationActivity) — a
                    //      one-time RESET_TOKEN (scope="password_reset"),
                    //      must call /auth/reset-password.
                    //   2. First-login-after-admin-created-account
                    //      (MainActivity, is_first_login) — the shop is
                    //      already logged in with a normal session TOKEN,
                    //      must call /auth/change-password instead.
                    // Previously both paths stored/read the same "TOKEN" key
                    // and always called resetPassword() — which meant the
                    // first-login path was silently broken (a normal login
                    // token never has scope="password_reset", so the backend
                    // always rejected it with 403). Fixed by branching on
                    // which token is actually present.
                    val prefs = getSharedPreferences("auth", MODE_PRIVATE)
                    val resetToken = prefs.getString("RESET_TOKEN", "") ?: ""
                    val sessionToken = prefs.getString("TOKEN", "") ?: ""

                    val response = when {
                        resetToken.isNotEmpty() ->
                            RetrofitClient.api.resetPassword(resetToken, ChangePasswordRequest(newPass))
                        sessionToken.isNotEmpty() ->
                            RetrofitClient.api.changePassword(sessionToken, newPass)
                        else -> {
                            Toast.makeText(this@ChangePasswordActivity, "Session expired. Please try again.", Toast.LENGTH_LONG).show()
                            finish()
                            return@launch
                        }
                    }

                    if (response.isSuccessful) {
                        Toast.makeText(this@ChangePasswordActivity, "Password updated successfully!", Toast.LENGTH_LONG).show()

                        // Report 5 fix: clear the one-time reset token now
                        // that it's been used — it's single-purpose and
                        // shouldn't linger in storage after this point.
                        // (No-op, harmlessly, on the first-login path.)
                        prefs.edit { remove("RESET_TOKEN") }

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
                    btnChangePassword.text = "Update password"
                    startCtaArrowAnimation()
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
                icon.setColorFilter(Color.parseColor("#0F6E56"))
                editText.setHintTextColor(Color.parseColor("#A99E88"))
            } else {
                icon.setColorFilter(Color.parseColor("#B8895A"))
                editText.setHintTextColor(Color.parseColor("#A99E88"))
            }
        }
    }

    // ================= PASSWORD VISIBILITY =================
    private fun setupPasswordToggle(editText: EditText, toggle: ImageView) {
        toggle.setOnClickListener {
            val visible = editText.transformationMethod == null
            if (visible) {
                editText.transformationMethod = PasswordTransformationMethod.getInstance()
                toggle.setImageResource(R.drawable.ic_lc_eye_off)
            } else {
                editText.transformationMethod = null
                toggle.setImageResource(R.drawable.ic_lc_eye)
            }
            editText.setSelection(editText.text.length)
        }
    }

    // ================= STRENGTH METER =================
    private fun updateStrengthMeter(password: String) {
        val segs = listOf(
            findViewById<View>(R.id.strengthSeg1),
            findViewById<View>(R.id.strengthSeg2),
            findViewById<View>(R.id.strengthSeg3),
            findViewById<View>(R.id.strengthSeg4)
        )
        val tvStrength = findViewById<TextView>(R.id.tvStrength)

        var score = 0
        if (password.length >= 6) score++
        if (password.length >= 10) score++
        if (password.any { it.isDigit() } && password.any { it.isLetter() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++

        val empty = Color.parseColor("#E3D9C4")
        val (label, color) = when {
            password.isEmpty() -> "" to empty
            score <= 1 -> "Weak password" to Color.parseColor("#C2553A")
            score == 2 -> "Fair password" to Color.parseColor("#B8895A")
            score == 3 -> "Good password" to Color.parseColor("#1D9E75")
            else -> "Strong password" to Color.parseColor("#0F6E56")
        }

        val filled = if (password.isEmpty()) 0 else score.coerceIn(1, 4)
        segs.forEachIndexed { i, seg ->
            seg.backgroundTintList = ColorStateList.valueOf(if (i < filled) color else empty)
        }
        tvStrength.text = label
        tvStrength.setTextColor(color)
    }

    // ================= MATCH CHECK =================
    private fun updateMatchState(newPass: String, confirmPass: String) {
        val row = findViewById<View>(R.id.matchRow)
        val tv = findViewById<TextView>(R.id.tvMatch)
        val iv = findViewById<ImageView>(R.id.ivMatch)

        if (confirmPass.isEmpty()) {
            row.visibility = View.INVISIBLE
            return
        }
        row.visibility = View.VISIBLE
        if (newPass == confirmPass) {
            tv.text = "Passwords match"
            tv.setTextColor(Color.parseColor("#1D9E75"))
            iv.setImageResource(R.drawable.ic_lc_check)
            iv.setColorFilter(Color.parseColor("#1D9E75"))
        } else {
            tv.text = "Passwords don't match"
            tv.setTextColor(Color.parseColor("#C2553A"))
            iv.setImageResource(R.drawable.ic_lc_check)
            iv.setColorFilter(Color.parseColor("#C2553A"))
        }
    }

    private fun simpleWatcher(onChange: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange() }
    }

    /** Show the arrow icon and loop its motion. */
    private fun startCtaArrowAnimation() {
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnChangePassword)
        btn.icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_cta_arrow)
        btn.post { (btn.icon as? android.graphics.drawable.Animatable)?.start() }
    }

    /** Hide the arrow icon (used while the button shows a loading label). */
    private fun hideCtaArrow() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnChangePassword).icon = null
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