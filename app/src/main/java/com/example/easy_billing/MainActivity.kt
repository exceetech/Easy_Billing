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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.DeviceUtils
import com.example.easy_billing.util.PastelColor
import com.example.easy_billing.util.applyPremiumClickAnimation
import com.google.android.material.card.MaterialCardView
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.HttpException

class MainActivity : BaseActivity() {

    private var entranceStarted = false

    /**
     * Formerly SplashActivity's job: if a token is already saved, verify it
     * against the server and route straight to Dashboard (or wipe it and
     * let the user log in normally if it's stale). No dedicated splash
     * screen anymore — this just runs quietly on top of the login screen.
     */
    /**
     * @param onStayingOnLogin called if (and only if) the check concludes the
     * user genuinely needs to see the login screen (no token, or a token
     * that turned out to be invalid) — the caller uses this to reveal the
     * form, which it hides up front so a still-valid session never flashes
     * the login screen before this redirects to Dashboard.
     */
    private fun checkExistingSession(onStayingOnLogin: () -> Unit) {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val token = prefs.getString("TOKEN", null)
        if (token.isNullOrEmpty()) {
            onStayingOnLogin()
            return
        }

        lifecycleScope.launch {
            // Shared with SplashActivity — see SessionCheck's doc comment
            // for why this used to be two hand-copied implementations
            // that drifted (this one was missing the BackendHealthStatus
            // calls Splash's had).
            val result = withContext(Dispatchers.IO) {
                com.example.easy_billing.util.SessionCheck.run(this@MainActivity, token)
            }

            when (result) {
                com.example.easy_billing.util.SessionCheck.Result.VALID -> {
                    startActivity(Intent(this@MainActivity, DashboardActivity::class.java))
                    finish()
                }
                com.example.easy_billing.util.SessionCheck.Result.ONBOARDING_INCOMPLETE -> {
                    startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
                    finish()
                }
                com.example.easy_billing.util.SessionCheck.Result.INVALID_TOKEN -> {
                    prefs.edit { remove("TOKEN") }
                    // stale token cleared — stay put, let the user log in normally
                    onStayingOnLogin()
                }
                com.example.easy_billing.util.SessionCheck.Result.WORKSPACE_CHANGED -> {
                    val intent = Intent(this@MainActivity, WorkspaceChangedActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    /**
     * Report 3 U-1: asks the user to confirm before a workspace switch wipes
     * [pendingCount] rows that never made it off this device. Returns true
     * if they chose to proceed anyway, false if they backed out.
     */
    private suspend fun confirmDestructiveWorkspaceSwitch(pendingCount: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.unsynced_data_will_be_lost)
                .setMessage(
                    "This device has $pendingCount unsynced record(s) from your " +
                        "current workspace. Switching now will permanently delete " +
                        "them from this device before they reach the server.\n\n" +
                        "Go back and let them sync first, or continue anyway?"
                )
                .setCancelable(false)
                .setPositiveButton(R.string.switch_anyway) { d, _ ->
                    d.dismiss()
                    if (cont.isActive) cont.resume(true) {}
                }
                .setNegativeButton(R.string.go_back) { d, _ ->
                    d.dismiss()
                    if (cont.isActive) cont.resume(false) {}
                }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
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

        // If a token is already saved, hide the login screen immediately —
        // before the very first frame — so it never flashes on screen while
        // checkExistingSession() verifies it and (usually) redirects
        // straight to Dashboard. Only revealed if the check concludes the
        // user genuinely needs to log in (no token, or a stale one).
        val rootView = findViewById<View>(android.R.id.content)
        val hasStoredToken = !getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null).isNullOrEmpty()
        if (hasStoredToken) {
            rootView.visibility = View.INVISIBLE
        }

        checkExistingSession {
            rootView.visibility = View.VISIBLE
        }

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
        startCtaArrowAnimation(R.id.btnLogin)
        btnLogin.setOnClickListener {

            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val deviceId = DeviceUtils.getDeviceId(this)

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, R.string.please_enter_all_fields, Toast.LENGTH_SHORT).show()
                com.example.easy_billing.util.UserEventLogger.logValidationFailed("Login", "credentials_missing")
                return@setOnClickListener
            }

            lifecycleScope.launch {

                try {

                    // ✅ INTERNET CHECK
                    if (!isInternetAvailable()) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.no_internet_connection,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }

                    btnLogin.isEnabled = false   // prevent double click
                    hideCtaArrow(R.id.btnLogin)

                    // ✅ CLOCK GATE (Phase 0): verify the device clock against
                    // internet time before authenticating. A wrong clock would
                    // stamp every bill with the wrong time, so block here.
                    when (val clock = verifyDeviceClock()) {
                        is ClockCheck.Skewed -> {
                            btnLogin.isEnabled = true
                            startCtaArrowAnimation(R.id.btnLogin)
                            showClockBlockedDialog(clock.driftMs) { btnLogin.performClick() }
                            return@launch
                        }
                        ClockCheck.OfflineUnverified -> {
                            btnLogin.isEnabled = true
                            startCtaArrowAnimation(R.id.btnLogin)
                            Toast.makeText(
                                this@MainActivity,
                                R.string.couldnt_verify_time_retry,
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        ClockCheck.Ok, ClockCheck.OfflineVerified -> { /* proceed */ }
                    }

                    val response = RetrofitClient.api.login(
                        username,
                        password,
                        deviceId   // ✅ ADD THIS
                    )
                    val token = response.access_token

                    if (token.isNullOrEmpty()) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.invalid_login_response,
                            Toast.LENGTH_LONG
                        ).show()
                        btnLogin.isEnabled = true
                        startCtaArrowAnimation(R.id.btnLogin)
                        return@launch
                    }

                    // ✅ CHECK SHOP_ID CHANGES
                    val prefs = getSharedPreferences("auth", MODE_PRIVATE)
                    val oldShopId = prefs.getInt("SHOP_ID", -1)

                    if (oldShopId != -1 && oldShopId != response.shop_id) {
                        // User logged into a different workspace (or a restored one).
                        // Must wipe the database to prevent cross-workspace data merging.
                        //
                        // Do not make this conditional or remove it: import_services
                        // has no shopId column and its queries have no shop filter,
                        // so this wipe is the only thing keeping one workspace's
                        // records out of another's list. See db/ImportService.kt.
                        //
                        // Report 3 U-1: previously wiped unconditionally, even with a
                        // sync mid-flight or rows that never made it off this device —
                        // that data was destroyed with no warning. Now checked first;
                        // if anything is unsynced, the user is asked to confirm before
                        // it's thrown away, instead of it silently vanishing.
                        val pendingCount = withContext(Dispatchers.IO) {
                            runCatching {
                                val oldDb = AppDatabase.getDatabase(applicationContext)
                                oldDb.billDao().countUnsynced() +
                                    oldDb.purchaseDao().countUnsynced() +
                                    oldDb.inventoryLogDao().countUnsynced() +
                                    oldDb.gstSalesInvoiceDao().countPending() +
                                    oldDb.creditNoteDao().countPending() +
                                    oldDb.purchaseReturnDao().countUnsynced() +
                                    oldDb.scrapDao().countUnsynced() +
                                    oldDb.importServiceDao().countPending()
                            }.getOrDefault(0)
                        }

                        val proceed = if (pendingCount > 0) {
                            confirmDestructiveWorkspaceSwitch(pendingCount)
                        } else {
                            true
                        }

                        if (!proceed) {
                            // User chose not to lose unsynced data. Nothing has been
                            // saved yet (TOKEN/SHOP_ID below), so the old session is
                            // untouched — just stop here and let them sync first.
                            Toast.makeText(
                                this@MainActivity,
                                R.string.switch_cancelled_sync_pending,
                                Toast.LENGTH_LONG
                            ).show()
                            btnLogin.isEnabled = true
                            startCtaArrowAnimation(R.id.btnLogin)
                            return@launch
                        }

                        withContext(Dispatchers.IO) {
                            try {
                                AppDatabase.getDatabase(applicationContext).clearAllTables()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    // ✅ SAVE TOKEN
                    prefs.edit {
                        putString("TOKEN", response.access_token)
                        putString("DEVICE_ID", deviceId)
                        putInt("SHOP_ID", response.shop_id)
                    }

                    // Post-login sync. Use flushPending(force=true), not the
                    // push-only requestSync(): a freshly-logged-in (or post-wipe)
                    // device needs to PULL server state, and we shouldn't depend on
                    // DashboardActivity.onResume being the first thing that pulls
                    // (first-login routes to ChangePassword; deep links may land
                    // elsewhere) — M4. Routed through the coordinator so it shares
                    // the single-flight lock; fire-and-forget so navigation isn't
                    // blocked on the network.
                    com.example.easy_billing.sync.SyncCoordinator
                        .get(this@MainActivity)
                        .flushPending(force = true)

                    updateLastOnlineTime()
                    // A successful login just proved the server is reachable
                    // and the new token is valid — stamp that directly rather
                    // than leaving a stale pre-login UNAUTHORIZED/UNREACHABLE
                    // verdict cached, which could otherwise get handed back
                    // out on the very next offline-timeout tick and force
                    // the user straight back out (see BackendHealthStatus.
                    // markVerifiedNow() for the full explanation).
                    com.example.easy_billing.util.BackendHealthStatus.markVerifiedNow()
                    android.util.Log.d("SESSION", "Session initialized")

                    // NAVIGATION
                    // Onboarding gate (plan §2.2): LoginResponse doesn't
                    // carry onboarding status itself (it only has
                    // is_first_login, a narrower, pre-existing signal
                    // about the forced password change) — rather than
                    // widen that response schema, route every
                    // non-first-login success through OnboardingActivity
                    // unconditionally. It self-resolves straight to
                    // Dashboard on its own next onResume if onboarding is
                    // already complete (see OnboardingActivity's fast
                    // path), so this costs one extra Activity frame on an
                    // already-onboarded login, not an extra gate a user
                    // can get stuck behind.
                    val next = if (response.is_first_login) {
                        ChangePasswordActivity::class.java
                    } else {
                        OnboardingActivity::class.java
                    }

                    startActivity(Intent(this@MainActivity, next))
                    finish()

                } catch (e: Exception) {

                    btnLogin.isEnabled = true
                    startCtaArrowAnimation(R.id.btnLogin)

                    // Was surfacing the raw backend error body / exception
                    // message to the user — an info-disclosure risk.
                    val code = (e as? retrofit2.HttpException)?.code()
                    com.example.easy_billing.util.UserEventLogger.logError(
                        "Login", "login_failed: ${e.javaClass.simpleName}${if (code != null) "_$code" else ""}"
                    )
                    Toast.makeText(this@MainActivity, R.string.something_went_wrong, Toast.LENGTH_LONG).show()
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

    /** Show the arrow icon and loop its motion. */
    private fun startCtaArrowAnimation(buttonId: Int) {
        val btn = findViewById<com.google.android.material.button.MaterialButton>(buttonId)
        btn.icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_cta_arrow)
        btn.post { (btn.icon as? android.graphics.drawable.Animatable)?.start() }
    }

    /** Hide the arrow icon (used while the button is processing). */
    private fun hideCtaArrow(buttonId: Int) {
        findViewById<com.google.android.material.button.MaterialButton>(buttonId).icon = null
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
                icon.setColorFilter(android.graphics.Color.parseColor("#0F6E56"))
                editText.setHintTextColor(android.graphics.Color.parseColor("#0F6E56"))
            } else {
                icon.setColorFilter(android.graphics.Color.parseColor("#B8895A"))
                editText.setHintTextColor(android.graphics.Color.parseColor("#A99E88"))
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
                if (isVisible) R.drawable.ic_lc_eye else R.drawable.ic_lc_eye_off
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