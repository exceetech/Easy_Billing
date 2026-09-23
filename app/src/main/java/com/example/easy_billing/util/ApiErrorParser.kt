package com.example.easy_billing.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Utility to extract short, single-sentence, human-readable error messages
 * from backend API exceptions, HTTP errors, and network issues.
 */
object ApiErrorParser {

    /**
     * Parses [e] into a single, concise sentence for clear reading.
     */
    fun parse(e: Throwable, context: Context? = null): String {
        return when (e) {
            is HttpException -> parseHttpException(e)
            is SocketTimeoutException -> "Connection timed out."
            is UnknownHostException, is ConnectException -> "Cannot connect to server."
            is SSLException -> "Secure connection failed."
            is IOException -> "Network connection problem."
            else -> {
                val msg = e.localizedMessage
                if (!msg.isNullOrBlank() && !msg.contains("Exception") && !msg.contains("retrofit") && !msg.contains("okhttp")) {
                    firstSentence(msg)
                } else {
                    "Something went wrong."
                }
            }
        }
    }

    private fun parseHttpException(e: HttpException): String {
        val code = e.code()
        val errorBody = try {
            e.response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }

        if (!errorBody.isNullOrBlank()) {
            val extracted = extractDetail(errorBody)
            if (!extracted.isNullOrBlank()) {
                return friendlyMessage(code, extracted)
            }
        }

        return when (code) {
            400 -> "Invalid details entered."
            401 -> "Incorrect email or password."
            403 -> "Account not active or access denied."
            404 -> "Account not found."
            409 -> "Account already exists."
            422 -> "Please check the entered information."
            429 -> "Too many attempts, please wait."
            in 500..599 -> "Server is temporarily unavailable."
            else -> "Server error ($code)."
        }
    }

    private fun extractDetail(body: String): String? {
        try {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                return null
            }

            if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)

                // 1. Check "detail" key (FastAPI standard)
                if (json.has("detail")) {
                    val detailObj = json.get("detail")
                    if (detailObj is String && detailObj.isNotBlank()) {
                        return detailObj
                    } else if (detailObj is JSONArray) {
                        // FastAPI validation errors: [{"loc": ["body", "phone"], "msg": "..."}]
                        val firstItem = detailObj.optJSONObject(0)
                        val msg = firstItem?.optString("msg")
                        if (!msg.isNullOrBlank()) {
                            val loc = firstItem.optJSONArray("loc")
                            val field = loc?.optString(loc.length() - 1)
                            return if (!field.isNullOrBlank() && field != "body") {
                                val cleanField = field.replace("_", " ").replaceFirstChar { it.uppercase() }
                                "$cleanField: $msg"
                            } else {
                                msg
                            }
                        }
                    }
                }

                // 2. Check "message" key
                if (json.has("message")) {
                    val msg = json.optString("message")
                    if (msg.isNotBlank()) return msg
                }

                // 3. Check "error" key
                if (json.has("error")) {
                    val err = json.optString("error")
                    if (err.isNotBlank()) return err
                }
            }
        } catch (_: Exception) {
            // Non-JSON or parsing failure — fallback to status code handling
        }
        return null
    }

    private fun friendlyMessage(code: Int, rawDetail: String): String {
        val detail = firstSentence(rawDetail)
        val lower = detail.lowercase()
        return when {
            lower == "invalid credentials" || lower.contains("invalid credentials") ->
                "Incorrect email or password."
            lower.contains("account not activated") || lower.contains("not activated") ->
                "Account is not activated yet."
            lower.contains("already logged in on another device") || lower.contains("another device") ->
                "Account is logged in on another device."
            lower.contains("email already registered") || lower.contains("already registered") ->
                "This email is already registered."
            lower.contains("device id missing") ->
                "Device ID is missing."
            lower.contains("rate limit") || code == 429 ->
                "Too many attempts, please wait."
            lower.startsWith("value error, ") ->
                detail.substringAfter("value error, ", "").ifEmpty { detail }
            lower.startsWith("value_error, ") ->
                detail.substringAfter("value_error, ", "").ifEmpty { detail }
            else -> detail
        }
    }

    private fun firstSentence(text: String): String {
        val cleaned = text.trim()
        val dotIndex = cleaned.indexOf(". ")
        val sentence = if (dotIndex != -1) cleaned.substring(0, dotIndex) else cleaned
        return if (!sentence.endsWith(".") && !sentence.endsWith("!")) {
            "$sentence."
        } else {
            sentence
        }
    }
}
