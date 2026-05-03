package com.example.easy_billing.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

/**
 * Opens the official CBIC HSN/GST rate list in the user's browser.
 * Wired to the small "HSN list" button on the Add-Product and
 * Update-Price dialogs.
 */
object HsnHelpLauncher {
    private const val URL = "https://cbic-gst.gov.in/gst-goods-services-rates.html"

    fun open(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, URL.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
