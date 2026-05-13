package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RegisterRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.applyPremiumClickAnimation
import kotlinx.coroutines.launch

class RegisterActivity : BaseActivity() {

    private var entranceStarted = false
    private var isRegistering = false   // 🔥 FLAG TO PREVENT DOUBLE CLICK

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
        window.sharedElementExitTransition = transition
        window.sharedElementReenterTransition = transition

        window.allowEnterTransitionOverlap = true
        window.allowReturnTransitionOverlap = true

        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.transitionBackgroundFadeDuration = 0

        postponeEnterTransition()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Cinematic Entrance choreography: Focus on branding and form headings
        val animatedItems = listOf(
            // Branding Side
            R.id.brandLogo, R.id.brandTitle,
            R.id.brandTagline, R.id.brandFeatures,
            // Form Headings ONLY
            R.id.lblFullName, R.id.lblEmail, R.id.lblPhone, R.id.lblShopName
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

                // Fallback: If no transition starts within 100ms, run entrance manually
                mainCard.postDelayed({
                    runCascadingEntrance(animatedItems)
                }, 100)

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

        val etFullName = findViewById<EditText>(R.id.etFullName)
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPhoneNumber = findViewById<EditText>(R.id.etPhoneNumber)
        val etShopName = findViewById<EditText>(R.id.ShopName)
        val btnRegister = findViewById<Button>(R.id.btnRegister)
        val tvBackToLogin = findViewById<TextView>(R.id.tvBackToLogin)

        val mainContainerCard = findViewById<View>(R.id.mainContainerCard)
        val registerContainer = findViewById<View>(R.id.registerContainer)
        val brandingPane = findViewById<View>(R.id.brandingPane)

        // Ensure inputs start unfocused
        etFullName.clearFocus()
        etEmail.clearFocus()
        etPhoneNumber.clearFocus()
        etShopName.clearFocus()

        setupInputField(R.id.nameContainer, R.id.etFullName, R.id.iconFullName)
        setupInputField(R.id.emailContainer, R.id.etEmail, R.id.iconEmail)
        setupInputField(R.id.phoneContainer, R.id.etPhoneNumber, R.id.iconPhone)
        setupInputField(R.id.shopContainer, R.id.ShopName, R.id.iconShop)

        // Premium split-screen entrance (mirrored)
        runEntranceAnimations(
            rightPane = brandingPane,
            formContainer = registerContainer,
            sequencedFields = listOf(
                R.id.registerHeader,
                R.id.nameContainer, R.id.emailContainer,
                R.id.phoneContainer, R.id.shopContainer,
                R.id.btnRegister, R.id.tvBackToLogin
            )
        )

        // 🔥 SLIDE-TO-LOGIN: Swipe the branding card to the left
        setupSlideToToggle(brandingPane, isSwipeRight = false) {
            val intent = Intent(this, MainActivity::class.java)
            val options = androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation(
                this, brandingPane, "shared_branding_pane"
            )
            startActivity(intent, options.toBundle())
        }

        btnRegister.applyPremiumClickAnimation()

        btnRegister.setOnClickListener {

            // 🔥 PREVENT MULTIPLE CLICKS
            if (isRegistering) return@setOnClickListener
            isRegistering = true
            btnRegister.isEnabled = false
            btnRegister.text = "Registering..."

            val name = etFullName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val phone = etPhoneNumber.text.toString().trim()
            val shopName = etShopName.text.toString().trim()

            if (name.isEmpty() || email.isEmpty() || phone.isEmpty() || shopName.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                resetButton(btnRegister)
                return@setOnClickListener
            }

            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Enter valid email", Toast.LENGTH_SHORT).show()
                resetButton(btnRegister)
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {

                    RetrofitClient.api.register(
                        RegisterRequest(
                            shop_name = shopName,
                            owner_name = name,
                            email = email,
                            phone = phone
                        )
                    )

                    showSuccessDialog(email)

                } catch (e: Exception) {

                    Toast.makeText(
                        this@RegisterActivity,
                        e.message ?: "Error",
                        Toast.LENGTH_SHORT
                    ).show()

                    resetButton(btnRegister)
                }
            }
        }

        // Back to Login — Mirroring the same transition logic as Login-to-Register
        tvBackToLogin.setOnClickListener {
            runExitTransition {
                val intent = Intent(this, MainActivity::class.java)
                val options = androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation(
                    this,
                    findViewById<View>(R.id.brandingPane),
                    "shared_branding_pane"
                )
                startActivity(intent, options.toBundle())
                finish() // Finish this to keep the stack clean
            }
        }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        runExitTransition {
            finishAfterTransition()
        }
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

    // 🔥 RESET BUTTON STATE
    private fun resetButton(button: Button) {
        isRegistering = false
        button.isEnabled = true
        button.text = "Create Account"
    }

    private fun showSuccessDialog(email: String) {

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Account Registered")
            .setMessage("Verification in process.\n\nOTP has been sent to your email.")
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ ->

                val intent = Intent(this, OtpVerificationActivity::class.java)
                intent.putExtra("EMAIL", email)
                startActivity(intent)
                finish()
            }
            .create()

        dialog.show()
    }

    /**
     * Premium entrance choreography (mirrored from login):
     *
     *   1. Right branding pane slides in from right (0 ms).
     *   2. Form container fades in from left (200 ms).
     *   3. Form fields stagger-cascade up (80 ms apart).
     */
    private fun runEntranceAnimations(
        rightPane: View,
        formContainer: View,
        sequencedFields: List<Int>
    ) {
        // Only the Shared Element Transition handles the branding pane.
        // Form remains still as per user request.
    }

    /**
     * Premium exit choreography: stagger-fade form fields out,
     * parallax the right pane, then invoke [onComplete].
     */
    private fun runExitTransition(onComplete: () -> Unit) {
        // System handles the Shared Element and Return transitions.
        // We just invoke onComplete to trigger finishAfterTransition().
        onComplete()
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

            // 🔥 THIS TRIGGERS BORDER HIGHLIGHT
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