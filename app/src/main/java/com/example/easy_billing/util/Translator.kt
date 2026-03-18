package com.example.easy_billing.util

object Translator {

    fun translate(text: String, lang: String): String {

        val map = mapOf(
            "Almonds" to mapOf(
                "ml" to "ബദാം",
                "hi" to "बादाम",
                "ta" to "பாதாம்",
                "te" to "బాదం",
                "kn" to "ಬಾದಾಮಿ"
            )
        )

        return map[text]?.get(lang) ?: text
    }
}