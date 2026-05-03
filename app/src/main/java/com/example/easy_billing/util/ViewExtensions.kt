package com.example.easy_billing.util

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

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