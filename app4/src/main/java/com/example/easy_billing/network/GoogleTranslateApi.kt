package com.example.easy_billing.network

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

data class GoogleTranslateRequest(
    val q: String,
    val target: String,
    val source: String = "en",
    val format: String = "text"
)

data class GoogleTranslateResponse(
    val data: TranslationData
)

data class TranslationData(
    val translations: List<TranslatedText>
)

data class TranslatedText(
    val translatedText: String
)

interface GoogleTranslateApi {

    @POST("language/translate/v2")
    suspend fun translate(
        @Query("key") apiKey: String,
        @Body request: GoogleTranslateRequest
    ): GoogleTranslateResponse
}