package com.example.easy_billing.ui.glass

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView

/*
 * Glass cards whose children (e.g. a tinted header band) must be clipped to the
 * card's own rounded background. The XML attribute android:clipToOutline only
 * works on Android 12+, but View.setClipToOutline works from Android 5, so these
 * containers turn it on in code and behave the same on every supported phone.
 * The outline comes from the background drawable (bg_lg_card etc.).
 */
class GlassClipLinearLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    init { clipToOutline = true }
}

class GlassClipFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    init { clipToOutline = true }
}

class GlassClipRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {
    init { clipToOutline = true }
}
