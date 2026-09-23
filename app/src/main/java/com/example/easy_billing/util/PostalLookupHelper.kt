package com.example.easy_billing.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Postal PIN code lookup helper for Indian addresses.
 *
 * Queries the public India Post postal API (api.postalpincode.in) to autofill
 * City/District, State, and post office localities when a user keys in a 6-digit PIN code.
 *
 * Includes an in-memory cache to avoid duplicate network calls and an offline
 * PIN-prefix fallback to detect the State even without an active internet connection.
 */
object PostalLookupHelper {

    data class PostalInfo(
        val pincode: String,
        val state: String,
        val district: String,
        val localities: List<String>
    )

    private val cache = ConcurrentHashMap<String, PostalInfo>()

    /**
     * Looks up address information for a 6-digit Indian PIN code.
     * Returns cached data if available, or fetches from the remote API.
     */
    suspend fun lookup(pincode: String): PostalInfo? = withContext(Dispatchers.IO) {
        val cleanPin = pincode.trim()
        if (cleanPin.length != 6 || !cleanPin.all { it.isDigit() }) {
            return@withContext null
        }

        cache[cleanPin]?.let { return@withContext it }

        var fetched: PostalInfo? = null

        try {
            val url = URL("https://api.postalpincode.in/pincode/$cleanPin")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "EasyBilling/1.0")
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()

                val rootArray = JSONArray(sb.toString())
                if (rootArray.length() > 0) {
                    val rootObj = rootArray.getJSONObject(0)
                    val status = rootObj.optString("Status", "")
                    if (status.equals("Success", ignoreCase = true) && !rootObj.isNull("PostOffice")) {
                        val postOffices = rootObj.getJSONArray("PostOffice")
                        var state = ""
                        var district = ""
                        val localities = mutableListOf<String>()

                        for (i in 0 until postOffices.length()) {
                            val po = postOffices.getJSONObject(i)
                            if (state.isEmpty()) {
                                state = po.optString("State", "")
                            }
                            if (district.isEmpty()) {
                                district = po.optString("District", "")
                            }
                            val name = po.optString("Name", "")
                            if (name.isNotEmpty() && !localities.contains(name)) {
                                localities.add(name)
                            }
                        }

                        if (state.isNotEmpty() || district.isNotEmpty()) {
                            fetched = PostalInfo(
                                pincode = cleanPin,
                                state = state,
                                district = district,
                                localities = localities
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Network error or timeout — proceed to offline fallback
        }

        // Offline fallback for state detection if remote API was unreachable
        if (fetched == null) {
            val fallbackState = getFallbackState(cleanPin)
            if (fallbackState.isNotEmpty()) {
                fetched = PostalInfo(
                    pincode = cleanPin,
                    state = fallbackState,
                    district = "",
                    localities = emptyList()
                )
            }
        }

        if (fetched != null) {
            cache[cleanPin] = fetched
        }

        fetched
    }

    /**
     * Estimates Indian state from the 2-digit PIN prefix when offline.
     */
    fun getFallbackState(pincode: String): String {
        if (pincode.length < 2) return ""
        val prefix2 = pincode.take(2).toIntOrNull() ?: return ""
        return when (prefix2) {
            11 -> "Delhi"
            12, 13 -> "Haryana"
            14, 15, 16 -> "Punjab"
            17 -> "Himachal Pradesh"
            18, 19 -> "Jammu and Kashmir"
            20, 21, 22, 23, 25, 27, 28 -> "Uttar Pradesh"
            24, 26 -> "Uttarakhand"
            30, 31, 32, 33, 34 -> "Rajasthan"
            36, 37, 38, 39 -> "Gujarat"
            40, 41, 42, 43, 44 -> "Maharashtra"
            45, 46, 47, 48 -> "Madhya Pradesh"
            49 -> "Chhattisgarh"
            50, 51, 52, 53 -> "Andhra Pradesh / Telangana"
            56, 57, 58, 59 -> "Karnataka"
            60, 61, 62, 63, 64 -> "Tamil Nadu"
            67, 68, 69 -> "Kerala"
            70, 71, 72, 73, 74 -> "West Bengal"
            75, 76, 77 -> "Odisha"
            78 -> "Assam"
            79 -> "North Eastern"
            80, 81, 82, 84, 85 -> "Bihar"
            83 -> "Jharkhand"
            else -> ""
        }
    }
}
