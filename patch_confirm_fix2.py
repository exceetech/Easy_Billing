import re
with open("app/src/main/java/com/example/easy_billing/ConfirmPaymentActivity.kt", "r") as f:
    content = f.read()

new_ui_logic = """
        tvServiceCharge = findViewById(R.id.tvServiceCharge)
        tvGst = findViewById(R.id.tvGst)
        tvTotal = findViewById(R.id.tvTotal)

        val rowGst = findViewById<View>(R.id.rowGst)
        val tvGstLabel = findViewById<TextView>(R.id.tvGstLabel)
        if (!gstEnabled) {
            rowGst.visibility = View.GONE
        } else {
            rowGst.visibility = View.VISIBLE
            val pctInt = gstPercent.toInt()
            val formatStr = if (gstPercent % 1.0 == 0.0) "${pctInt}%" else "${gstPercent}%"
            tvGstLabel.text = "GST ($formatStr)"
        }
"""

content = re.sub(
    r'tvServiceCharge = findViewById\(R\.id\.tvServiceCharge\).*?rowGst\.visibility = View\.VISIBLE\n\s*\}',
    new_ui_logic.strip(),
    content,
    flags=re.DOTALL
)

with open("app/src/main/java/com/example/easy_billing/ConfirmPaymentActivity.kt", "w") as f:
    f.write(content)
