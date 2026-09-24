with open("app/src/main/java/com/example/easy_billing/ConfirmPaymentActivity.kt", "r") as f:
    content = f.read()

import re
ui_update_logic = """
        tvServiceCharge = findViewById(R.id.tvServiceCharge)
        tvGst = findViewById(R.id.tvGst)
        tvFinalPrice = findViewById(R.id.tvFinalPrice)

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
    r'tvServiceCharge = findViewById\(R\.id\.tvServiceCharge\)\s+tvGst = findViewById\(R\.id\.tvGst\)\s+tvFinalPrice = findViewById\(R\.id\.tvFinalPrice\)',
    ui_update_logic.strip(),
    content
)

with open("app/src/main/java/com/example/easy_billing/ConfirmPaymentActivity.kt", "w") as f:
    f.write(content)
