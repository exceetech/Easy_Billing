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
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
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
    
    private var entranceStarted = false
    override fun onCreate(savedInstanceState: Bundle?) {
        // 🔥 Premium Shared Element Transition config
        window.requestFeature(android.view.Window.FEATURE_ACTIVITY_TRANSITIONS)
        window.sharedElementsUseOverlay = false
        
        val transition = android.transition.TransitionSet().apply {
            addTransition(android.transition.ChangeBounds())
            addTransition(android.transition.ChangeTransform())
            addTransition(android.transition.ChangeImageTransform())
            addTransition(android.transition.ChangeClipBounds())
            duration = 850L
            interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
        }
        window.sharedElementEnterTransition = transition
        window.sharedElementReturnTransition = transition
        window.sharedElementReenterTransition = transition
        window.sharedElementExitTransition = transition
        
        window.allowEnterTransitionOverlap = true
        window.allowReturnTransitionOverlap = true
        
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.transitionBackgroundFadeDuration = 0

        postponeEnterTransition()
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
              // Cinematic Entrance choreography: Focus on branding and form headings
        val animatedItems = listOf(
            // Branding Side
            R.id.brandLogo, R.id.brandTitle, 
            R.id.brandTagline, R.id.brandFeatures,
            // Form Headings ONLY
            R.id.lblEmail, R.id.lblPassword
        )

        // Initial State: Invisible and slightly shifted down
        animatedItems.forEach { id ->
            findViewById<View>(id)?.apply {
                alpha = 0f
                translationY = 40f
            }
        }
        
        // Start transition once the layout is ready
        val mainCard = findViewById<View>(R.id.mainContainerCard)
        mainCard.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                mainCard.viewTreeObserver.removeOnPreDrawListener(this)
                startPostponedEnterTransition()
                
                // Fallback: Immediate trigger for first launch
                mainCard.postOnAnimation {
                    runCascadingEntrance(animatedItems)
                }
                
                return true
            }
        })

        // Execute Cascading Entrance after transition finishes
        window.sharedElementEnterTransition.addListener(object : android.transition.Transition.TransitionListener {
            override fun onTransitionEnd(transition: android.transition.Transition) {
                runCascadingEntrance(animatedItems)
            }
            override fun onTransitionStart(transition: android.transition.Transition) {}
            override fun onTransitionCancel(transition: android.transition.Transition) {}
            override fun onTransitionPause(transition: android.transition.Transition) {}
            override fun onTransitionResume(transition: android.transition.Transition) {}
        })

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
        val brandingPane = findViewById<View>(R.id.brandingPane)

        setupInputField(R.id.usernameContainer, R.id.etUsername, R.id.iconUsername)
        setupInputField(R.id.passwordContainer, R.id.etPassword, R.id.iconPassword)

        // Premium split-screen entrance
        runEntranceAnimations(
            leftPane = mainContainerCard,
            formContainer = loginContainer,
            sequencedFields = listOf(
                R.id.loginHeader,
                R.id.lblEmail, R.id.usernameContainer,
                R.id.lblPassword, R.id.passwordContainer,
                R.id.tvForgotPassword, R.id.btnLogin, R.id.tvRegister
            )
        )

        // 🔥 PASSWORD VISIBILITY TOGGLE
        setupPasswordToggle(findViewById(R.id.etPassword), findViewById(R.id.btnTogglePassword))

        // 🔥 SLIDE-TO-REGISTER: Swipe the branding card to the right
        setupSlideToToggle(brandingPane, isSwipeRight = true) {
            val intent = Intent(this, RegisterActivity::class.java)
            val options = androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation(
                this, brandingPane, "shared_branding_pane"
            )
            startActivity(intent, options.toBundle())
        }

        // Register — premium cinematic exit
        tvRegister.setOnClickListener {
            runExitTransition {
                val intent = Intent(this, RegisterActivity::class.java)
                val options = androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation(
                    this,
                    findViewById<View>(R.id.brandingPane),
                    "shared_branding_pane"
                )
                startActivity(intent, options.toBundle())
            }
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
        // Only the Shared Element Transition handles the leftPane.
        // Form remains still as per user request.
    }

    /**
     * Premium exit choreography: stagger-fade form fields out,
     * parallax the left pane, then invoke [onComplete].
     */
    private fun runExitTransition(onComplete: () -> Unit) {
        // System handles the Shared Element and Fade transitions.
        // We just invoke onComplete to trigger the activity switch.
        onComplete()
    }

    override fun onResume() {
        super.onResume()
        // Re-entrance: reset all views and re-run entrance animation
        val card = findViewById<View>(R.id.mainContainerCard)
        val login = findViewById<View>(R.id.loginContainer)
        val img = findViewById<View>(R.id.imgIllustration)

        card?.scaleX = 1f
        card?.scaleY = 1f
        img?.translationX = 0f

        val fields = listOf(
            R.id.loginHeader, R.id.usernameContainer,
            R.id.passwordContainer, R.id.tvForgotPassword,
            R.id.btnLogin, R.id.tvRegister, R.id.loginFooter
        )
        fields.forEach { id ->
            findViewById<View>(id)?.apply { alpha = 1f; translationY = 0f }
        }

        runEntranceAnimations(
            leftPane = card ?: return,
            formContainer = login ?: return,
            sequencedFields = fields
        )
    }




    private fun runCascadingEntrance(items: List<Int>) {
        if (entranceStarted) return
        entranceStarted = true

        items.forEachIndexed { index, id ->
            findViewById<View>(id)?.animate()
                ?.alpha(1f)
                ?.translationY(0f)
                ?.setStartDelay(index * 120L) // Slower stagger
                ?.setDuration(1000L) // Slower reveal
                ?.setInterpolator(android.view.animation.OvershootInterpolator(1.5f)) // More elastic
                ?.start()
        }
    }

    private fun setupSlideToToggle(view: View, isSwipeRight: Boolean, onAction: () -> Unit) {
        var startX = 0f
        val threshold = 120 // pixels (snappier)

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startX
                    
                    // Visual feedback: Drag the card slightly (Parallax)
                    if (isSwipeRight && deltaX > 0) {
                        v.translationX = deltaX * 0.15f // 15% resistance
                    } else if (!isSwipeRight && deltaX < 0) {
                        v.translationX = deltaX * 0.15f
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val endX = event.rawX
                    val deltaX = endX - startX
                    
                    val triggered = if (isSwipeRight) deltaX > threshold else deltaX < -threshold
                    
                    if (triggered) {
                        // Reset parallax instantly so the transition starts from a clean position
                        v.translationX = 0f
                        onAction()
                    } else {
                        // Snap back gracefully with a premium bounce
                        v.animate()
                            .translationX(0f)
                            .setDuration(600)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                            .start()
                    }
                    true
                }
                else -> false
            }
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
    private fun setupPasswordToggle(editText: EditText, toggleButton: ImageView) {
        var isVisible = false
        toggleButton.setOnClickListener {
            isVisible = !isVisible

            // 1. Toggle visibility
            editText.transformationMethod = if (isVisible) {
                HideReturnsTransformationMethod.getInstance()
            } else {
                PasswordTransformationMethod.getInstance()
            }

            // 2. Update icon
            toggleButton.setImageResource(
                if (isVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off
            )

            // 3. Keep cursor at the end
            editText.setSelection(editText.text.length)

            // 4. Premium Spring Animation
            toggleButton.animate()
                .scaleX(1.3f).scaleY(1.3f)
                .setDuration(120)
                .setInterpolator(OvershootInterpolator())
                .withEndAction {
                    toggleButton.animate()
                        .scaleX(1.0f).scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }
    }
}