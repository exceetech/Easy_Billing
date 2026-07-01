package com.example.easy_billing.util

/**
 * Maps common product unit strings to the GST-portal standard
 * Unit Quantity Codes (UQC) used in GSTR-1 HSN Summary and
 * GSTR-3B filings.
 *
 * If the input unit doesn't match any known key, the mapper
 * returns the raw unit uppercased — which lets the user see
 * exactly what was entered and signals they should supply an
 * explicit "Official UQC" via the product master UI.
 */
object UqcMapper {

    private val UNIT_TO_UQC = mapOf(
        // Pieces / numbers
        "piece"    to "NOS",
        "pieces"   to "NOS",
        "pcs"      to "NOS",
        "pc"       to "NOS",
        "unit"     to "NOS",
        "units"    to "NOS",
        "nos"      to "NOS",
        "number"   to "NOS",
        "numbers"  to "NOS",
        "item"     to "NOS",
        "items"    to "NOS",

        // Weight
        "kg"       to "KGS",
        "kgs"      to "KGS",
        "kilogram" to "KGS",
        "kilograms" to "KGS",
        "gram"     to "GMS",
        "grams"    to "GMS",
        "g"        to "GMS",
        "gm"       to "GMS",
        "gms"      to "GMS",
        "mg"       to "MGS",
        "milligram" to "MGS",
        "tonne"    to "TON",
        "ton"      to "TON",
        "mt"       to "TON",

        // Volume
        "litre"    to "LTR",
        "liter"    to "LTR",
        "litres"   to "LTR",
        "liters"   to "LTR",
        "ltr"      to "LTR",
        "l"        to "LTR",
        "ml"       to "MLT",
        "millilitre" to "MLT",
        "milliliter" to "MLT",

        // Length / area
        "meter"    to "MTR",
        "metre"    to "MTR",
        "meters"   to "MTR",
        "metres"   to "MTR",
        "mtr"      to "MTR",
        "m"        to "MTR",
        "cm"       to "CMS",
        "centimeter" to "CMS",
        "mm"       to "MMT",
        "millimeter" to "MMT",
        "km"       to "KME",
        "sqm"      to "SQM",
        "sqft"     to "SQF",
        "sqyd"     to "SQY",

        // Packing
        "box"      to "BOX",
        "boxes"    to "BOX",
        "pack"     to "PAC",
        "packs"    to "PAC",
        "packet"   to "PAC",
        "packets"  to "PAC",
        "bottle"   to "BTL",
        "bottles"  to "BTL",
        "btl"      to "BTL",
        "bag"      to "BAG",
        "bags"     to "BAG",
        "bundle"   to "BDL",
        "bundles"  to "BDL",
        "roll"     to "ROL",
        "rolls"    to "ROL",
        "dozen"    to "DZN",
        "doz"      to "DZN",
        "set"      to "SET",
        "sets"     to "SET",
        "pair"     to "PRS",
        "pairs"    to "PRS",
        "can"      to "CAN",
        "cans"     to "CAN",
        "tube"     to "TUB",
        "tubes"    to "TUB",
        "drum"     to "DRM",
        "drums"    to "DRM",
    )

    /**
     * Converts [unit] to the closest GST UQC.
     * Returns an existing explicit [officialUqc] unchanged if non-blank.
     * Falls back to the uppercased raw unit when no mapping is found.
     */
    fun resolve(unit: String?, officialUqc: String?): String {
        if (!officialUqc.isNullOrBlank()) return officialUqc.uppercase().trim()
        if (unit.isNullOrBlank()) return "NOS"
        val key = unit.trim().lowercase()
        return UNIT_TO_UQC[key] ?: unit.uppercase().trim()
    }

    /** Convenience: resolve from unit only (no explicit UQC set). */
    fun fromUnit(unit: String?): String = resolve(unit, null)

    /** GST UQC code → human-readable full name. */
    private val UQC_FULL_NAMES = mapOf(
        "BAG" to "Bag",
        "BDL" to "Bundle",
        "BOX" to "Box",
        "BTL" to "Bottle",
        "CAN" to "Can / Tin",
        "CMS" to "Centimeter",
        "DRM" to "Drum",
        "DZN" to "Dozen",
        "GMS" to "Grams",
        "KGS" to "Kilograms",
        "KME" to "Kilometer",
        "LTR" to "Litre",
        "MGS" to "Milligrams",
        "MLT" to "Millilitre",
        "MMT" to "Millimeter",
        "MTR" to "Meter",
        "NOS" to "Numbers",
        "OTH" to "Others",
        "PAC" to "Pack",
        "PRS" to "Pairs",
        "ROL" to "Roll",
        "SET" to "Set",
        "SQF" to "Square Feet",
        "SQM" to "Square Meter",
        "SQY" to "Square Yard",
        "TON" to "Tonne",
        "TUB" to "Tube"
    )

    /**
     * Sorted list of all valid GSTR-1 UQC codes (bare codes only),
     * suitable when you need just the code string.
     */
    val ALL_UQC_CODES: List<String> = UQC_FULL_NAMES.keys.sorted()

    /**
     * Sorted list of display strings in the form "CODE (Full Name)",
     * e.g. "KGS (Kilograms)". Use this to populate dropdowns.
     */
    val ALL_UQC_DISPLAY: List<String> =
        UQC_FULL_NAMES.entries
            .map { (code, name) -> "$code ($name)" }
            .sortedBy { it }

    /**
     * Converts a display string like "KGS (Kilograms)" back to
     * the bare UQC code "KGS".  If [display] is already a bare
     * code (no parenthesis), it is returned uppercased as-is.
     */
    fun displayToCode(display: String?): String? {
        if (display.isNullOrBlank()) return null
        return display.substringBefore(" (").trim().uppercase().ifBlank { null }
    }

    /**
     * Converts a bare UQC code like "KGS" to its display string
     * "KGS (Kilograms)".  Returns null when [code] is blank or
     * not in the registry so callers can leave the spinner empty.
     */
    fun codeToDisplay(code: String?): String? {
        if (code.isNullOrBlank()) return null
        val upper = code.trim().uppercase()
        val name = UQC_FULL_NAMES[upper] ?: return upper   // bare code as fallback
        return "$upper ($name)"
    }
}
