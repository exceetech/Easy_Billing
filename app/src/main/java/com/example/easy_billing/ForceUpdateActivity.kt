package com.example.easy_billing

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.easy_billing.util.AppUpdatePolicy

/**
 * ForceUpdateActivity
 *
 * Shown at start-up when the admin console sets a minimum app version higher than this build.
 * It replaces the app and cannot be dismissed with Back: the only way forward is the store.
 *
 * Nothing is cleared. The shop's local data and unsynced bills stay on the device and sync after the update.
 * Not a BaseActivity, for the same reasons as AccountSuspendedActivity: no forced orientation, and the
 * offline-session timeout must not log anyone out from under this screen.
 *
 * Coming back from the store (still outdated) lands here again via SplashActivity's check on the next launch;
 * after a real update the check passes and the normal start-up runs.
 */
class ForceUpdateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_URL = "url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_force_update)
        com.example.easy_billing.util.UserEventLogger.logAction("ForceUpdate", "opened")

        findViewById<TextView>(R.id.tvUpdateMessage).text =
            intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() } ?: getString(R.string.force_update_default_message)
        val url = intent.getStringExtra(EXTRA_URL)
        findViewById<Button>(R.id.btnUpdateNow).setOnClickListener { AppUpdatePolicy.openStore(this, url) }
    }

    @Deprecated("Disabled — this version is no longer supported; the only way forward is to update.")
    override fun onBackPressed() {
        // Intentionally blocked.
    }
}
