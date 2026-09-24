with open("app/src/main/java/com/example/easy_billing/SubscriptionActivity.kt", "r") as f:
    content = f.read()

content = content.replace(
    "plans = RetrofitClient.api.getPlans(token).filter { it.duration_days in knownCycleDays } { it.duration_days in knownCycleDays }",
    "plans = RetrofitClient.api.getPlans(token).filter { it.duration_days in knownCycleDays }"
)

import re
blurb_logic = """
            val gstEnabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("sub_gst_enabled", false)
            val blurbText = if (gstEnabled) "+ GST + service charges" else "+ service charges"

            val blurb = TextView(this).apply {
                text = blurbText
"""
content = re.sub(
    r'val blurb = TextView\(this\)\.apply \{\s*text = "\+ GST \+ service charges"',
    blurb_logic.strip(),
    content
)

with open("app/src/main/java/com/example/easy_billing/SubscriptionActivity.kt", "w") as f:
    f.write(content)
