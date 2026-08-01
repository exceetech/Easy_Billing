package com.example.easy_billing.util

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import com.example.easy_billing.R

/**
 * Connectivity helper for the LIVE / OFFLINE status pills shown in the
 * dashboard drawers and the invoice header.
 *
 *   • [isOnline]        — one-shot check of the active network.
 *   • [applyStatus]     — paints a pill (background + label) LIVE or OFFLINE.
 *   • [registerCallback]/[unregister] — reactive updates when the network
 *     comes/goes. Callback fires on a binder thread, so callers should
 *     marshal UI work back to the main thread (runOnUiThread).
 */
object NetworkUtils {

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        // Also require NET_CAPABILITY_VALIDATED (real, reachable internet —
        // not just "connected to an access point"), matching
        // BaseActivity.isInternetAvailable() / SessionTimeoutGuard's check.
        // Without this, the LIVE pill could show LIVE on a Wi-Fi network with
        // no actual internet (captive portal, dead router WAN) — a different
        // answer to "am I online?" than the code that actually enforces the
        // offline logout, which is exactly the kind of drift that made this
        // worth flagging.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun applyStatus(container: View?, label: TextView?, online: Boolean) {
        container?.setBackgroundResource(
            if (online) R.drawable.bg_drawer_live_pill
            else R.drawable.bg_drawer_offline_pill
        )
        label?.text = if (online) "LIVE" else "OFFLINE"
    }

    /**
     * Blinks the status [dot] only while [online]; when offline the blink is
     * cancelled and the dot held solid. Pass the previously-returned animator
     * back in on each call and store the result.
     */
    fun blinkDot(dot: View?, current: ObjectAnimator?, online: Boolean): ObjectAnimator? {
        dot ?: return current
        return if (online) {
            current ?: ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } else {
            current?.cancel()
            dot.alpha = 1f
            null
        }
    }

    fun registerCallback(
        context: Context,
        onChange: (Boolean) -> Unit
    ): ConnectivityManager.NetworkCallback? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(true)
            override fun onLost(network: Network) = onChange(isOnline(context))
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                onChange(
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                )
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        return cb
    }

    fun unregister(context: Context, cb: ConnectivityManager.NetworkCallback?) {
        cb ?: return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }
}
