package com.example.easy_billing.util

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

import android.animation.*
import android.graphics.Color
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

fun View.applyPremiumClickAnimation() {

    val pressScale = 0.96f

    this.setOnTouchListener { v, event ->

        when (event.action) {

            MotionEvent.ACTION_DOWN -> {

                // 🔹 CLEAN PRESS (no tilt, no Z tricks)
                v.animate()
                    .scaleX(pressScale)
                    .scaleY(pressScale)
                    .setDuration(90)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                // 🔹 SUBTLE DARKEN (feels like pressure)
                v.alpha = 0.92f
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                // 🔹 SMOOTH RELEASE (no bounce)
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                // 🔹 RESTORE
                v.alpha = 1f
            }
        }

        false
    }
}

/* ------------------ ENTRANCE ANIMATION ------------------ */

fun View.runPremiumEntrance(sequencedFields: List<View>) {

    this.alpha = 0f
    this.translationY = 60f

    this.animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(100L)
        .setDuration(800L)
        .setInterpolator(DecelerateInterpolator(2.5f))
        .start()

    sequencedFields.forEachIndexed { index, view ->
        view.alpha = 0f
        view.translationY = 30f
        view.scaleX = 0.95f
        view.scaleY = 0.95f

        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(250L + index * 50L)
            .setDuration(700L)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.0f))
            .start()
    }
}

/* ------------------ HEADER ANIMATION ------------------ */

fun List<TextView>.startPremiumHeaderOscillation() {

    this.forEach { tv ->

        val alpha = ObjectAnimator.ofFloat(tv, View.ALPHA, 0.6f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        val scaleX = ObjectAnimator.ofFloat(tv, View.SCALE_X, 0.98f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        val scaleY = ObjectAnimator.ofFloat(tv, View.SCALE_Y, 0.98f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        val translateX = ObjectAnimator.ofFloat(tv, View.TRANSLATION_X, 0f, 4f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        val glow = ValueAnimator.ofFloat(0.2f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE

            addUpdateListener {
                val value = it.animatedValue as Float
                tv.setShadowLayer(
                    8f * value,
                    0f,
                    0f,
                    Color.parseColor("#00FF41")
                )
            }
        }

        AnimatorSet().apply {
            playTogether(alpha, scaleX, scaleY, translateX, glow)
            start()
        }
    }
}

/* ------------------ INPUT FIELD HANDLER ------------------ */

fun setupPremiumInputField(
    container: View,
    editText: EditText,
    icon: ImageView
) {
    container.setOnClickListener {
        editText.requestFocus()
    }

    editText.setOnFocusChangeListener { _, hasFocus ->

        container.isActivated = hasFocus

        if (hasFocus) {
            icon.setColorFilter(Color.parseColor("#00C853"))
            editText.setHintTextColor(Color.parseColor("#00C853"))
        } else {
            icon.setColorFilter(Color.parseColor("#8F9098"))
            editText.setHintTextColor(Color.parseColor("#71717A"))
        }
    }
}