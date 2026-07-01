package com.example.easy_billing.util

import com.example.easy_billing.network.GoogleTranslateRequest
import com.example.easy_billing.network.RetrofitClient

object GoogleTranslator {

    private const val API_KEY = "AIzaSyBl7pJsqfYZ-rSLVwldHPhf5jyzWgeVVzw"

    suspend fun translate(text: String, language: String): String {

        if (language == "en") return text

        return try {

            val response = RetrofitClient.googleTranslateApi.translate(
                API_KEY,
                GoogleTranslateRequest(
                    q = text,
                    target = language
                )
            )

            response.data.translations[0].translatedText

        } catch (e: Exception) {

            text
        }
    }
}