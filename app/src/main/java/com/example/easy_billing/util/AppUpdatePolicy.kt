package com.example.easy_billing.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.core.content.pm.PackageInfoCompat
import com.example.easy_billing.BuildConfig
import com.example.easy_billing.ForceUpdateActivity
import com.example.easy_billing.R
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimum / recommended app version, set by an administrator in the admin console.
 *
 *  - [Policy.updateRequired]    this build is older than the minimum: block everything with [ForceUpdateActivity].
 *  - [Policy.updateRecommended] this build is older than the recommended one: show a dismissible banner.
 *
 * The check FAILS OPEN. No network, a slow server, or an odd answer means "no policy": a shop must never be
 * locked out of billing because this one request failed. It runs against the public endpoint
 * `GET /app-config/version-policy`, which needs no login (so it also works before sign-in).
 *
 * Plain HttpURLConnection on purpose: no Retrofit interceptors (session, suspension, device checks) apply
 * to a request that has to work for signed-out and outdated builds alike.
 */
object AppUpdatePolicy {

    data class Policy(
        val updateRequired: Boolean,
        val updateRecommended: Boolean,
        val recommendedCode: Long,
        val message: String?,
        val updateUrl: String?,
    )

    private const val PREFS = "app_update"
    private const val KEY_DISMISSED = "dismissed_recommended_code"
    private const val TIMEOUT_MS = 3000

    /** Last answer, kept so the Dashboard can show the banner without asking again. */
    @Volatile
    var latest: Policy? = null
        private set

    @Volatile
    private var bannerShownThisSession = false

    fun currentVersionCode(context: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    } catch (_: Exception) {
        Long.MAX_VALUE // unknown build: never claim it is outdated
    }

    /** Blocking network call: run it on Dispatchers.IO. Returns null when the policy could not be read. */
    fun fetch(context: Context): Policy? {
        var connection: HttpURLConnection? = null
        return try {
            val base = BuildConfig.API_BASE_URL.trimEnd('/')
            val url = URL("$base/app-config/version-policy?version_code=${currentVersionCode(context)}")
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != 200) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            Policy(
                updateRequired = json.optBoolean("update_required", false),
                updateRecommended = json.optBoolean("update_recommended", false),
                recommendedCode = json.optLong("recommended_version_code", 0L),
                message = json.optString("message").ifBlank { null },
                updateUrl = json.optString("update_url").ifBlank { null },
            ).also { latest = it }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun showForceUpdate(context: Context, policy: Policy) {
        val intent = Intent(context.applicationContext, ForceUpdateActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(ForceUpdateActivity.EXTRA_MESSAGE, policy.message)
            putExtra(ForceUpdateActivity.EXTRA_URL, policy.updateUrl)
        }
        context.applicationContext.startActivity(intent)
    }

    /** Opens the store page: the admin's link first, then the Play app, then Play in a browser. */
    fun openStore(context: Context, url: String?) {
        val candidates = listOfNotNull(
            url?.takeIf { it.startsWith("https://") || it.startsWith("market://") },
            "market://details?id=${context.packageName}",
            "https://play.google.com/store/apps/details?id=${context.packageName}",
        )
        for (target in candidates) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // try the next one
            }
        }
    }

    /** Shows the "newer version available" banner once per app start, and not again once dismissed for that version. */
    fun maybeShowRecommendedBanner(activity: Activity, anchor: View) {
        val policy = latest ?: return
        if (!policy.updateRecommended || bannerShownThisSession) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_DISMISSED, 0L) >= policy.recommendedCode) return
        bannerShownThisSession = true
        Snackbar.make(anchor, policy.message ?: activity.getString(R.string.app_update_recommended_message), Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.app_update_action) { openStore(activity, policy.updateUrl) }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    // Swiped away or the action tapped: do not nag again for this version.
                    if (event == DISMISS_EVENT_SWIPE || event == DISMISS_EVENT_ACTION) {
                        prefs.edit().putLong(KEY_DISMISSED, policy.recommendedCode).apply()
                    }
                }
            })
            .show()
    }
}
