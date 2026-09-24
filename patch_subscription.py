import re

with open("app/src/main/java/com/example/easy_billing/SubscriptionActivity.kt", "r") as f:
    content = f.read()

load_plans_logic = """
            try {
                // Fetch config
                val config = RetrofitClient.api.getSubscriptionConfig()
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("sub_gst_enabled", config.gst_enabled)
                    .putFloat("sub_gst_percent", config.gst_percent)
                    .apply()

                plans = RetrofitClient.api.getPlans(token).filter { it.duration_days in knownCycleDays }
"""

content = re.sub(
    r'try\s*\{\s*plans = RetrofitClient\.api\.getPlans\(token\)\.filter',
    load_plans_logic.strip(),
    content
)

# Update "+ GST + service charges" to be dynamic
gst_string_logic = """
            val gstEnabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("sub_gst_enabled", false)
            val noteText = if (gstEnabled) "+ GST + service charges" else "+ service charges"

            val tvNote = TextView(this).apply {
                text = noteText
"""

content = re.sub(
    r'val tvNote = TextView\(this\)\.apply \{\s*text = "\+ GST \+ service charges"',
    gst_string_logic.strip(),
    content
)

with open("app/src/main/java/com/example/easy_billing/SubscriptionActivity.kt", "w") as f:
    f.write(content)
