package com.example.easy_billing.util

/**
 * In-memory switch for the hidden admin tools (Clear All Bills, Factory
 * Reset, Send Diagnostic Report). Switched on/off by tapping the version
 * text in Settings 7 times. Never persisted: it is off again after the app
 * restarts, and Settings turns it off when the screen is left.
 */
object AdminMode {
    @Volatile
    var enabled: Boolean = false
}
