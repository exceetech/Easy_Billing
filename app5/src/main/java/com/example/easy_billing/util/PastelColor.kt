package com.example.easy_billing.util

import android.graphics.Color
import kotlin.random.Random

object PastelColor {

    fun random(): Int {

        val red = Random.Default.nextInt(150, 256)
        val green = Random.Default.nextInt(150, 256)
        val blue = Random.Default.nextInt(150, 256)

        return Color.rgb(red, green, blue)
    }
}