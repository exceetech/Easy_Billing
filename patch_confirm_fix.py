with open("app/src/main/java/com/example/easy_billing/ConfirmPaymentActivity.kt", "r") as f:
    content = f.read()

# I need to fix my previous patch where I added `tvTaxNote` logic.
import re
new_ui_logic = """
        tvServiceCharge = findViewById(R.id.tvServiceCharge)
        tvGst = findViewById(R.id.tvGst)
        tvTotal = findViewById(R.id.tvTotal)

        val rowGst = findViewById<View>(R.id.rowGst)
        if (!gstEnabled) {
            rowGst.visibility = View.GONE
        } else {
            rowGst.visibility = View.VISIBLE
        }
"""
content = re.sub(
    r'tvServiceCharge = findViewById\(R\.id\.tvServiceCharge\).*?tvTaxNote\.text = "Includes 2% service charge and \$\{pctInt\}% GST"\n\s*\}',
    new_ui_logic.strip(),
    content,
    flags=re.DOTALL
)

with open("app/src/main/java/com/example/easy_billing/ConfirmPaymentActivity.kt", "w") as f:
    f.write(content)
