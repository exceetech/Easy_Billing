package com.example.easy_billing.util

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

/**
 * Floating-label behaviour for the glass input fields.
 *
 * Each field carries its name twice by design: as the `hint` inside the box
 * (visible while empty) and as a small label directly above it. Showing both
 * at once reads as duplication, so the label stays **invisible** until the
 * field has content — the name appears to move up out of the box as you type,
 * and the box keeps showing the value.
 *
 * The label reserves its space (INVISIBLE, not GONE) so nothing shifts when
 * it appears.
 *
 * Wiring is by convention, not ids: a `TextView` tagged `floatlabel` is bound
 * to the first editor found in the sibling immediately after it.
 */
object FloatingLabels {

    private const val TAG = "floatlabel"

    /** Wires every tagged label found under [root]. */
    fun bind(root: View) {
        if (root !is ViewGroup) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is TextView && child.tag == TAG && i + 1 < root.childCount) {
                val container = root.getChildAt(i + 1)
                firstEditor(container)?.let { attach(child, it, container) }
            }
            bind(child)
        }
    }

    /**
     * Shows or hides a field **and** its label together. Use this instead of
     * setting the container's visibility directly, otherwise a hidden field
     * leaves its label behind.
     */
    fun setFieldVisible(container: View, visible: Boolean) {
        container.visibility = if (visible) View.VISIBLE else View.GONE
        labelFor(container)?.let { label ->
            label.visibility = when {
                !visible -> View.GONE
                firstEditor(container)?.text.isNullOrEmpty() -> View.INVISIBLE
                else -> View.VISIBLE
            }
        }
    }

    private fun labelFor(container: View): TextView? {
        val parent = container.parent as? ViewGroup ?: return null
        val i = parent.indexOfChild(container)
        if (i <= 0) return null
        val candidate = parent.getChildAt(i - 1)
        return if (candidate is TextView && candidate.tag == TAG) candidate else null
    }

    private fun firstEditor(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                firstEditor(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun attach(label: TextView, editor: EditText, container: View) {
        fun sync() {
            label.visibility = when {
                container.visibility == View.GONE -> View.GONE
                editor.text.isNullOrEmpty() -> View.INVISIBLE
                else -> View.VISIBLE
            }
        }
        sync()
        editor.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = sync()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }
}
