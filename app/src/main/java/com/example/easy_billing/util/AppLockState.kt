package com.example.easy_billing.util

/**
 * Tracks whether the app is currently "locked" behind Quick Unlock
 * (PIN/fingerprint), independent of whether the session token itself is
 * still valid. This is what makes Quick Unlock behave like a real app lock
 * screen — shown every time the app is opened or brought back from the
 * background — rather than only when a fresh server login is required.
 *
 * Starts `true` so a cold app launch is locked by default. Set back to
 * `true` whenever [EasyBillingApp]'s lifecycle callbacks see every Activity
 * leave the foreground (the user left the app), and set to `false` only
 * after QuickUnlockActivity confirms a correct PIN or fingerprint.
 */
object AppLockState {
    @Volatile
    var isLocked: Boolean = true
        private set

    fun lock() {
        isLocked = true
    }

    fun unlock() {
        isLocked = false
    }
}
