package com.example.easy_billing

import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.DeviceUtils
import com.example.easy_billing.util.QuickUnlockManager
import kotlinx.coroutines.launch

/**
 * Quick Unlock — the "easy login" screen. It has two modes, chosen by the
 * caller via [EXTRA_MODE]:
 *
 *  • [MODE_SETUP]  — shown ONCE, right after a real email+password login,
 *    offering to remember that login behind a 4-digit PIN (and fingerprint,
 *    if the device supports it) so the user never has to type their email
 *    and password again on this device.
 *
 *  • [MODE_UNLOCK] — shown instead of the full login form whenever the app
 *    needs a fresh login and Quick Unlock is already set up. Correct PIN
 *    or fingerprint silently replays the saved login and continues exactly
 *    like a normal sign-in.
 *
 * Both modes share the same PIN-pad layout (activity_quick_unlock.xml) —
 * this mirrors the familiar phone-unlock pattern most people already know
 * from their lock screen, rather than inventing a new interaction.
 *
 * Multi-account devices: Quick Unlock data is keyed per account (see
 * QuickUnlockManager), so [EXTRA_USERNAME] tells this screen WHICH
 * account's PIN to check in [MODE_UNLOCK] / [MODE_LOCK]. When the caller
 * doesn't know it (e.g. a cold app launch before any session exists), it's
 * omitted and this screen falls back to the device's last-active account.
 */
class QuickUnlockActivity : BaseActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_NEXT_CLASS = "next_class"
        /** MODE_SETUP only: true when this is re-running setup to change an
         *  already-configured PIN (e.g. from Settings), false/absent for a
         *  genuine first-time setup right after login. Wording-only. */
        const val EXTRA_IS_CHANGE = "is_change"
        const val MODE_SETUP = "setup"
        const val MODE_UNLOCK = "unlock"
        // Real app-lock screen — shown on every app open/resume when Quick
        // Unlock is configured, regardless of whether the session token is
        // still valid. See BaseActivity.checkAppLock() and AppLockState.
        const val MODE_LOCK = "lock"
    }

    private var mode = MODE_UNLOCK
    private var enteredPin = StringBuilder()
    private var firstPin: String? = null // set mode only: PIN typed in step 1, waiting for confirmation
    private var awaitingConfirm = false

    // The account this screen is checking the PIN/fingerprint for.
    // MODE_SETUP always has it (the just-completed login). MODE_UNLOCK /
    // MODE_LOCK use EXTRA_USERNAME when the caller knows it, otherwise the
    // device's last-active account.
    private var targetUsername: String? = null

    private lateinit var dots: List<View>
    private lateinit var tvTitleBold: TextView
    private lateinit var tvTitleItalic: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvError: TextView
    private lateinit var tvAlt: TextView
    private lateinit var tvSelectAnotherAccount: TextView
    private lateinit var tvStepChip: TextView
    private lateinit var ivIcon: ImageView
    private lateinit var ringPulse1: View
    private lateinit var ringPulse2: View
    private lateinit var btnBiometric: ImageView
    private var pulseAnimator: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_unlock)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK
        targetUsername = intent.getStringExtra(EXTRA_USERNAME)
            ?: QuickUnlockManager.getLastUsername(this)

        dots = listOf(
            findViewById(R.id.dot1), findViewById(R.id.dot2),
            findViewById(R.id.dot3), findViewById(R.id.dot4)
        )
        tvTitleBold = findViewById(R.id.tvUnlockTitleBold)
        tvTitleItalic = findViewById(R.id.tvUnlockTitleItalic)
        tvSubtitle = findViewById(R.id.tvUnlockSubtitle)
        tvError = findViewById(R.id.tvUnlockError)
        tvAlt = findViewById(R.id.tvUnlockAlt)
        tvSelectAnotherAccount = findViewById(R.id.tvSelectAnotherAccount)
        tvStepChip = findViewById(R.id.tvUnlockStepChip)
        ivIcon = findViewById(R.id.ivUnlockIcon)
        ringPulse1 = findViewById(R.id.ringPulse1)
        ringPulse2 = findViewById(R.id.ringPulse2)
        btnBiometric = findViewById(R.id.btnBiometric)
        startPulseRings()

        val keys = mapOf(
            R.id.btnKey0 to "0", R.id.btnKey1 to "1", R.id.btnKey2 to "2",
            R.id.btnKey3 to "3", R.id.btnKey4 to "4", R.id.btnKey5 to "5",
            R.id.btnKey6 to "6", R.id.btnKey7 to "7", R.id.btnKey8 to "8",
            R.id.btnKey9 to "9"
        )
        keys.forEach { (id, digit) ->
            findViewById<TextView>(id).setOnClickListener { onDigit(digit) }
        }
        findViewById<ImageView>(R.id.btnBackspace).setOnClickListener { onBackspace() }

        // No account to check against (e.g. MODE_UNLOCK/MODE_LOCK reached
        // on a device that has never completed Quick Unlock setup) — fall
        // straight back to the real login form instead of showing a PIN
        // pad that can never succeed.
        if (mode != MODE_SETUP && targetUsername == null) {
            goToFullLogin()
            return
        }

        if (mode == MODE_SETUP) {
            val isChange = intent.getBooleanExtra(EXTRA_IS_CHANGE, false)
            com.example.easy_billing.util.UserEventLogger.logAction("QuickUnlock", "setup_opened")
            setIcon(R.drawable.ic_lucide_shield_check)
            setStep(1)
            tvSelectAnotherAccount.visibility = View.GONE
            if (isChange) {
                setTitle("Update your", "Quick Unlock PIN")
                tvSubtitle.text = "Enter a new 4-digit PIN"
                tvAlt.text = "Cancel"
            } else {
                setTitle("Create a", "Quick Unlock PIN")
                tvSubtitle.text = "So you don't have to type your email and password every time"
                tvAlt.text = "Skip for now"
            }
            tvAlt.setOnClickListener { finishSetup(enabledBiometric = false, skipped = true) }
        } else if (mode == MODE_LOCK) {
            com.example.easy_billing.util.UserEventLogger.logAction("QuickUnlock", "lock_shown")
            setIcon(R.drawable.ic_lucide_lock)
            setTitle("Welcome", "back")
            tvSubtitle.text = "Enter your 4-digit PIN to continue"
            tvSelectAnotherAccount.visibility = View.GONE
            tvAlt.text = "Forgot PIN? Sign out"
            tvAlt.setOnClickListener { forgotPinSignOut() }

            if (QuickUnlockManager.hasBiometricEnabled(this, targetUsername!!) && canUseBiometric()) {
                btnBiometric.visibility = View.VISIBLE
                btnBiometric.setOnClickListener { showBiometricPrompt() }
                showBiometricPrompt()
            }
        } else {
            com.example.easy_billing.util.UserEventLogger.logAction("QuickUnlock", "unlock_opened")
            setIcon(R.drawable.ic_lucide_lock)
            setTitle("Enter your", "PIN")
            tvSubtitle.text = "Enter your 4-digit PIN to continue"
            tvAlt.text = getString(R.string.use_password_instead)
            tvAlt.setOnClickListener { goToFullLogin() }

            val accounts = QuickUnlockManager.getAccounts(this)
            if (accounts.size > 1) {
                tvSelectAnotherAccount.visibility = View.VISIBLE
                tvSelectAnotherAccount.text = getString(R.string.select_another_account)
                tvSelectAnotherAccount.setOnClickListener {
                    val intent = Intent(this, AccountSelectionActivity::class.java)
                    startActivity(intent)
                    finish()
                }
            } else {
                tvSelectAnotherAccount.visibility = View.GONE
            }

            if (QuickUnlockManager.hasBiometricEnabled(this, targetUsername!!) && canUseBiometric()) {
                btnBiometric.visibility = View.VISIBLE
                btnBiometric.setOnClickListener { showBiometricPrompt() }
                // Offer fingerprint immediately — one less tap for a returning user.
                showBiometricPrompt()
            }
        }

        updateDots()
    }

    /** Bold + serif-italic split title, matching the Manage Quick Unlock
     *  dialog's title treatment. */
    private fun setTitle(bold: String, italic: String) {
        tvTitleBold.text = bold
        tvTitleItalic.text = italic
    }

    private fun setIcon(resId: Int) {
        ivIcon.setImageResource(resId)
    }

    /** MODE_SETUP only: shows/updates the "Step X of 2" chip above the icon.
     *  step == 0 hides it (MODE_UNLOCK / MODE_LOCK have no multi-step flow). */
    private fun setStep(step: Int) {
        if (step <= 0) {
            tvStepChip.visibility = View.GONE
            return
        }
        tvStepChip.visibility = View.VISIBLE
        tvStepChip.text = if (step == 1) getString(R.string.quick_unlock_step_1) else getString(R.string.quick_unlock_step_2)
    }

    /** Two faint rings behind the icon tile, pulsing outward on a staggered
     *  loop — an ambient "this screen is listening for your PIN" glow,
     *  matching the dialog mockup. Purely decorative; stopped in onDestroy
     *  so it doesn't keep animating (and leaking the view) after the
     *  screen is gone. */
    private fun startPulseRings() {
        pulseAnimator?.cancel()
        val ring1 = buildRingPulse(ringPulse1, startDelay = 0L)
        val ring2 = buildRingPulse(ringPulse2, startDelay = 900L)
        pulseAnimator = AnimatorSet().apply {
            playTogether(ring1, ring2)
            start()
        }
    }

    private fun buildRingPulse(ring: View, startDelay: Long): AnimatorSet {
        ring.scaleX = 0.78f
        ring.scaleY = 0.78f
        ring.alpha = 0.9f
        val scaleX = ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.78f, 1.35f).apply { duration = 2600 }
        val scaleY = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.78f, 1.35f).apply { duration = 2600 }
        val alpha = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.9f, 0f).apply { duration = 2600 }
        val set = AnimatorSet()
        set.playTogether(scaleX, scaleY, alpha)
        set.interpolator = AccelerateDecelerateInterpolator()
        set.startDelay = startDelay
        set.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!isFinishing && !isDestroyed) {
                    ring.scaleX = 0.78f
                    ring.scaleY = 0.78f
                    ring.alpha = 0.9f
                    set.startDelay = 0
                    set.start()
                }
            }
        })
        return set
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDestroy()
    }

    /**
     * In MODE_LOCK, back must NOT reveal the Activity underneath without a
     * correct PIN/fingerprint — that would defeat the whole point of a lock
     * screen. Send the app to the background instead, same as pressing
     * Home; the lock screen is still there next time it's foregrounded.
     */
    override fun onBackPressed() {
        if (mode == MODE_LOCK) {
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    private fun canUseBiometric(): Boolean {
        val manager = BiometricManager.from(this)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                com.example.easy_billing.util.UserEventLogger.logAction("QuickUnlock", "biometric_success")
                if (mode == MODE_LOCK) {
                    unlockAppAndFinish()
                } else {
                    performUnlockLogin()
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User cancelled or too many attempts — just fall back to the PIN pad, no error shown.
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Easy Billing")
            .setSubtitle("Use your fingerprint to continue")
            .setNegativeButtonText("Use PIN instead")
            .build()
        prompt.authenticate(info)
    }

    private fun onDigit(digit: String) {
        if (enteredPin.length >= 4) return
        tvError.text = ""
        enteredPin.append(digit)
        updateDots()
        if (enteredPin.length == 4) {
            findViewById<View>(R.id.keypad).postDelayed({ onPinComplete() }, 120)
        }
    }

    private fun onBackspace() {
        if (enteredPin.isNotEmpty()) {
            enteredPin.deleteCharAt(enteredPin.length - 1)
            tvError.text = ""
            updateDots()
        }
    }

    private fun updateDots() {
        dots.forEachIndexed { index, dot ->
            val shouldFill = index < enteredPin.length
            val isFilled = dot.tag == true
            dot.setBackgroundResource(
                if (shouldFill) R.drawable.bg_pin_dot_filled else R.drawable.bg_pin_dot_empty
            )
            if (shouldFill && !isFilled) {
                // Little bounce as each digit lands — matches the dialog's
                // "dot-pop" feedback instead of a flat color swap.
                dot.scaleX = 0.4f
                dot.scaleY = 0.4f
                dot.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f))
                    .start()
            }
            dot.tag = shouldFill
        }
    }

    private fun shakeAndClear(message: String) {
        tvError.text = message
        val dotRow = findViewById<View>(R.id.dotRow)
        dotRow.animate().translationX(24f).setDuration(60).withEndAction {
            dotRow.animate().translationX(-24f).setDuration(60).withEndAction {
                dotRow.animate().translationX(0f).setDuration(60).start()
            }.start()
        }.start()
        enteredPin = StringBuilder()
        updateDots()
    }

    private fun onPinComplete() {
        val pin = enteredPin.toString()

        if (mode == MODE_SETUP) {
            if (!awaitingConfirm) {
                firstPin = pin
                awaitingConfirm = true
                enteredPin = StringBuilder()
                updateDots()
                setStep(2)
                setIcon(R.drawable.ic_lucide_check)
                setTitle("Confirm", "your PIN")
                tvSubtitle.text = "Enter the same 4 digits again"
            } else {
                if (pin == firstPin) {
                    offerBiometricThenFinish(pin)
                } else {
                    awaitingConfirm = false
                    firstPin = null
                    setStep(1)
                    setIcon(R.drawable.ic_lucide_shield_check)
                    if (intent.getBooleanExtra(EXTRA_IS_CHANGE, false)) {
                        setTitle("Update your", "Quick Unlock PIN")
                        tvSubtitle.text = "Enter a new 4-digit PIN"
                    } else {
                        setTitle("Create a", "Quick Unlock PIN")
                        tvSubtitle.text = "So you don't have to type your email and password every time"
                    }
                    shakeAndClear("PINs didn't match — try again")
                }
            }
        } else {
            val username = targetUsername
            if (username != null && QuickUnlockManager.verifyPin(this, username, pin)) {
                com.example.easy_billing.util.UserEventLogger.logAction("QuickUnlock", "pin_success")
                if (mode == MODE_LOCK) {
                    unlockAppAndFinish()
                } else {
                    performUnlockLogin()
                }
            } else {
                com.example.easy_billing.util.UserEventLogger.logAction("QuickUnlock", "pin_failed")
                shakeAndClear("Incorrect PIN")
            }
        }
    }

    /** MODE_LOCK success path: no network call needed — the session is
     * already valid, this just dismisses the lock screen back to whatever
     * Activity was underneath it. */
    private fun unlockAppAndFinish() {
        com.example.easy_billing.util.AppLockState.unlock()
        finish()
    }

    /** MODE_LOCK "forgot PIN": can't silently bypass the lock, so this
     * signs the user all the way out instead of leaving them stuck. Only
     * THIS account's Quick Unlock data is cleared — a different account
     * that's also set up Quick Unlock on this device is untouched. */
    private fun forgotPinSignOut() {
        getSharedPreferences("auth", MODE_PRIVATE).edit().remove("TOKEN").apply()
        targetUsername?.let { QuickUnlockManager.clear(this, it) }
        com.example.easy_billing.util.AppLockState.unlock()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun offerBiometricThenFinish(pin: String) {
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""

        if (canUseBiometric()) {
            showEnableBiometricDialog(
                onEnable = {
                    QuickUnlockManager.setup(this, pin, username, password, enableBiometric = true)
                    finishSetup(enabledBiometric = true, skipped = false)
                },
                onDecline = {
                    QuickUnlockManager.setup(this, pin, username, password, enableBiometric = false)
                    finishSetup(enabledBiometric = false, skipped = false)
                }
            )
        } else {
            QuickUnlockManager.setup(this, pin, username, password, enableBiometric = false)
            finishSetup(enabledBiometric = false, skipped = false)
        }
    }

    /** Themed replacement for a plain AlertDialog#setTitle/#setMessage —
     *  same champagne card shell as Manage Quick Unlock, shown right after
     *  a correct PIN confirmation on a device that supports biometrics.
     *  Not cancelable via back/outside-tap, matching the original dialog's
     *  setCancelable(false): the user must make an explicit choice here. */
    private fun showEnableBiometricDialog(onEnable: () -> Unit, onDecline: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_enable_biometric, null)
        val btnEnable = dialogView.findViewById<View>(R.id.btnEnableBiometric)
        val btnDecline = dialogView.findViewById<View>(R.id.btnJustPin)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnEnable.setOnClickListener {
            dialog.dismiss()
            onEnable()
        }
        btnDecline.setOnClickListener {
            dialog.dismiss()
            onDecline()
        }

        dialog.show()
    }

    private fun finishSetup(enabledBiometric: Boolean, skipped: Boolean) {
        com.example.easy_billing.util.UserEventLogger.logAction(
            "QuickUnlock", if (skipped) "setup_skipped" else "setup_completed_biometric=$enabledBiometric"
        )
        // This screen only ever runs right after a real password login
        // (MainActivity already unlocked for that), but belt-and-suspenders:
        // finishing (or skipping) setup must never leave the session locked,
        // or the very next Activity's onResume would immediately re-challenge
        // for a PIN that either doesn't exist yet (skipped) or was just typed.
        com.example.easy_billing.util.AppLockState.unlock()

        val nextClassName = intent.getStringExtra(EXTRA_NEXT_CLASS)
        val nextClass = try {
            if (nextClassName != null) Class.forName(nextClassName) else DashboardActivity::class.java
        } catch (e: Exception) {
            DashboardActivity::class.java
        }

        if (skipped) {
            startActivity(Intent(this, nextClass))
            finish()
            return
        }

        // A brief celebratory beat before handing off — a checkmark pop and
        // a toast, matching the confirm-step success moment, instead of
        // navigating away the instant the PIN is saved.
        Toast.makeText(this, "Quick Unlock is ready", Toast.LENGTH_SHORT).show()
        setStep(0)
        setIcon(R.drawable.ic_lucide_check)
        setTitle("Quick Unlock", "is ready")
        tvSubtitle.text = "You're all set — use your PIN or fingerprint next time"
        tvError.text = ""
        ivIcon.scaleX = 0.5f
        ivIcon.scaleY = 0.5f
        ivIcon.animate().scaleX(1f).scaleY(1f).setDuration(300)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.5f))
            .start()
        findViewById<View>(R.id.keypad).postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, nextClass))
                finish()
            }
        }, 900)
    }

    private fun goToFullLogin() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * Replays the saved login after a correct PIN/fingerprint. Scope note:
     * Quick Unlock is only ever offered to a user who has already been
     * through onboarding and password setup once, so — unlike the full
     * MainActivity login — this intentionally skips the first-login /
     * workspace-change branches and goes straight to Dashboard on success.
     * If the saved password has since changed elsewhere (e.g. on another
     * device), the login call below fails and we fall back to the real
     * login form rather than getting stuck.
     */
    private fun performUnlockLogin() {
        val username0 = targetUsername
        val creds = username0?.let { QuickUnlockManager.getCredentials(this, it) }
        if (creds == null) {
            goToFullLogin()
            return
        }
        val (username, password) = creds

        if (!isInternetAvailable()) {
            Toast.makeText(this, R.string.no_internet_connection, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val deviceId = DeviceUtils.getDeviceId(this@QuickUnlockActivity)
                val response = RetrofitClient.api.login(username, password, deviceId, quickUnlock = "true")
                val token = response.access_token
                if (token.isNullOrEmpty()) {
                    shakeAndClear("Couldn't sign in — try your password instead")
                    return@launch
                }

                val prefs = getSharedPreferences("auth", MODE_PRIVATE)
                prefs.edit()
                    .putString("TOKEN", token)
                    .putString("DEVICE_ID", deviceId)
                    .putString("USERNAME", username)
                    .putInt("SHOP_ID", response.shop_id)
                    .apply()
                // Keep the device's "last active account" pointer in sync —
                // it only otherwise moves on a full email+password login, so
                // without this a PIN/fingerprint unlock for a DIFFERENT
                // account than the one last fully logged in would leave it
                // stale (see BaseActivity.checkAppLock's doc comment).
                QuickUnlockManager.markActive(this@QuickUnlockActivity, username)


                com.example.easy_billing.sync.SyncCoordinator
                    .get(this@QuickUnlockActivity)
                    .flushPending(force = true)
                com.example.easy_billing.util.BackendHealthStatus.markVerifiedNow()

                // A correct PIN/fingerprint here just re-proved identity —
                // without this, Dashboard's very next onResume would
                // immediately re-trigger the app-wide lock screen since
                // AppLockState.isLocked was never cleared for this path,
                // creating a PIN-after-PIN loop right after unlocking.
                com.example.easy_billing.util.AppLockState.unlock()

                startActivity(Intent(this@QuickUnlockActivity, DashboardActivity::class.java))
                finish()
            } catch (e: Exception) {
                com.example.easy_billing.util.UserEventLogger.logError(
                    "QuickUnlock", "replay_login_failed: ${e.javaClass.simpleName}"
                )

                // Only an HTTP status the backend uses to REJECT this
                // account/device outright counts as proof the saved PIN
                // should stop working. A timeout, unreachable host, DNS
                // failure, 5xx server error, or malformed response is a
                // transient problem with THIS attempt, not with the saved
                // credentials — wiping Quick Unlock over those would force
                // a full re-login AND a brand-new PIN setup for no real
                // reason, the very opposite of "cleared only on device
                // reset." Those cases just let the user retry from here.
                val httpCode = (e as? retrofit2.HttpException)?.code()
                val isAuthRejection = httpCode == 401 || httpCode == 403 || httpCode == 409

                if (!isAuthRejection) {
                    shakeAndClear("Couldn't reach the server — try again")
                    return@launch
                }

                // Genuine rejection: saved credentials no longer work (401 —
                // e.g. password changed elsewhere), a real device mismatch
                // (403), or the backend refused to let this replay re-bind a
                // reset device (409). Clear this account's Quick Unlock data
                // (which also drops it from the account picker) and fall
                // back to a real login. Other accounts on this device are
                // unaffected.
                QuickUnlockManager.clear(this@QuickUnlockActivity, username)
                val message = if (httpCode == 409) {
                    "Your device access was reset — please sign in with your password"
                } else {
                    "Please sign in again with your password"
                }
                Toast.makeText(this@QuickUnlockActivity, message, Toast.LENGTH_LONG).show()
                goToFullLogin()
            }
        }
    }
}
