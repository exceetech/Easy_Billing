package com.example.easy_billing.util

/**
 * Address formatting and parsing utility for Indian addresses.
 *
 * Handles conversion between structured fields (Street, Area/Locality, City, State, PIN)
 * and unified formatted address strings (e.g., "Mukkam, Balussery" or "Shop 4, Mukkam, Kozhikode, Kerala - 673602").
 */
object AddressHelper {

    data class StructuredAddress(
        val street: String = "",
        val area: String = "",
        val city: String = "",
        val state: String = "",
        val pincode: String = ""
    ) {
        val isBlank: Boolean
            get() = street.isBlank() && area.isBlank() && city.isBlank() && state.isBlank() && pincode.isBlank()
    }

    private val knownStates = listOf(
        "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh",
        "Goa", "Gujarat", "Haryana", "Himachal Pradesh", "Jharkhand", "Karnataka",
        "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya", "Mizoram",
        "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu",
        "Telangana", "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal",
        "Delhi", "Jammu and Kashmir", "Ladakh", "Puducherry", "Chandigarh"
    )

    /**
     * Formats structured address components into a single clean comma-separated string.
     */
    fun formatAddress(
        street: String,
        area: String,
        city: String,
        state: String,
        pincode: String
    ): String {
        val cleanStreet = street.trim()
        val cleanArea = area.trim()
        val cleanCity = city.trim()
        val cleanState = state.trim()
        val cleanPincode = pincode.trim()

        val stateAndPin = when {
            cleanState.isNotEmpty() && cleanPincode.isNotEmpty() -> "$cleanState - $cleanPincode"
            cleanState.isNotEmpty() -> cleanState
            cleanPincode.isNotEmpty() -> cleanPincode
            else -> ""
        }

        val parts = mutableListOf<String>()
        if (cleanStreet.isNotEmpty()) parts.add(cleanStreet)
        if (cleanArea.isNotEmpty()) parts.add(cleanArea)
        if (cleanCity.isNotEmpty()) parts.add(cleanCity)
        if (stateAndPin.isNotEmpty()) parts.add(stateAndPin)

        return parts.joinToString(", ")
    }

    /**
     * Parses an existing combined address string into structured components.
     */
    fun parseAddress(raw: String?): StructuredAddress {
        if (raw.isNullOrBlank()) return StructuredAddress()

        var text = raw.trim()
        var pincode = ""
        var state = ""

        // 1. Extract 6-digit pincode if present
        val pinRegex = Regex("""\b(\d{6})\b""")
        val pinMatch = pinRegex.find(text)
        if (pinMatch != null) {
            pincode = pinMatch.value
            text = text.replace(pinMatch.value, "").trim()
        }

        // Clean trailing/leading hyphens or commas
        text = text.replace(Regex("""[-–—,]\s*$"""), "").trim()

        // 2. Extract State if recognized
        for (st in knownStates) {
            val stateRegex = Regex("""\b${Regex.escape(st)}\b""", RegexOption.IGNORE_CASE)
            if (stateRegex.containsMatchIn(text)) {
                state = st
                text = text.replace(stateRegex, "").trim()
                break
            }
        }

        // Clean extra punctuation left after extraction
        text = text.replace(Regex("""[-–—,]\s*$"""), "").trim()

        // 3. Split remaining parts by comma
        val segments = text.split(",")
            .map { it.trim().trim('-', '–', '—').trim() }
            .filter { it.isNotEmpty() }

        var street = ""
        var area = ""
        var city = ""

        when {
            segments.size >= 3 -> {
                street = segments.dropLast(2).joinToString(", ")
                area = segments[segments.size - 2]
                city = segments.last()
            }
            segments.size == 2 -> {
                area = segments[0]
                city = segments[1]
            }
            segments.size == 1 -> {
                area = segments[0]
            }
        }

        return StructuredAddress(
            street = street,
            area = area,
            city = city,
            state = state,
            pincode = pincode
        )
    }
}
