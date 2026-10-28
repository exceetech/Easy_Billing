package com.example.easy_billing.util

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.easy_billing.R

/**
 * Keeps the field you are typing in visible when the keyboard opens.
 *
 * The app runs full-screen (system bars hidden, window not fitting system windows), and since
 * Android 11 / the Android 15 target, that means the system no longer shrinks or lifts the page for
 * the keyboard. This gives the page the keyboard's height as bottom padding instead, so the screen's
 * own ScrollView (or list) ends right above the keyboard, then scrolls the focused field into view.
 *
 * It only acts when the keyboard really covers the window. If the system already moved the window
 * (floating dialogs, or windows that still fit system windows) the padding stays 0, so nothing is
 * ever handled twice. No colours, sizes or layouts are changed.
 */
object KeyboardInsets {

    /** Install on an activity after its content view has been set. */
    fun install(activity: Activity) = install(activity.window)

    /** Install on any window (activity or full-screen dialog). Safe to call more than once. */
    fun install(window: Window?) {
        window ?: return
        val decor = window.decorView
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.getTag(R.id.keyboard_insets_installed) != null) return
        content.setTag(R.id.keyboard_insets_installed, true)

        // Resize mode, keeping whatever "keyboard state" flag the screen already chose.
        val current = window.attributes.softInputMode
        window.setSoftInputMode(
            (current and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                setBottom(window, v, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                insets
            }
            ViewCompat.requestApplyInsets(content)
        } else {
            // Older Android: measure how much of the window the keyboard hides.
            val density = decor.resources.displayMetrics.density
            decor.viewTreeObserver.addOnGlobalLayoutListener {
                val visible = Rect()
                decor.getWindowVisibleDisplayFrame(visible)
                val loc = IntArray(2)
                decor.getLocationOnScreen(loc)
                val hidden = (loc[1] + decor.height) - visible.bottom
                setBottom(window, content, if (hidden > 100 * density) hidden else 0)
            }
        }
    }

    private fun setBottom(window: Window, content: View, bottom: Int) {
        if (content.paddingBottom == bottom) return
        content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottom)
        if (bottom > 0) revealFocus(window)
    }

    /** After the page has shrunk, bring the focused field (with a little room below it) into view. */
    private fun revealFocus(window: Window) {
        val focused = window.currentFocus ?: return
        val extra = (16 * focused.resources.displayMetrics.density).toInt()
        focused.post {
            if (focused.isAttachedToWindow) {
                focused.requestRectangleOnScreen(Rect(0, 0, focused.width, focused.height + extra), false)
            }
        }
    }
}
