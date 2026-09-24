with open("app/src/main/java/com/example/easy_billing/ConfirmPaymentActivity.kt", "r") as f:
    content = f.read()

# Replace hardcoded gstPercent
content = content.replace(
    "private val gstPercent = 18.0",
    "private var gstPercent = 18.0\n    private var gstEnabled = false"
)

# Fetch from SharedPreferences in onCreate
on_create_fetch = """
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        gstEnabled = prefs.getBoolean("sub_gst_enabled", false)
        gstPercent = prefs.getFloat("sub_gst_percent", 18.0f).toDouble()

        Checkout.preload(applicationContext)
"""
content = content.replace("Checkout.preload(applicationContext)", on_create_fetch.strip())

# recomputeTaxesAndTotal logic
recompute_logic = """
    private fun recomputeTaxesAndTotal(newSubtotalPaise: Int) {
        subtotalPaise = newSubtotalPaise
        serviceChargePaise = Math.round(subtotalPaise * (serviceChargePercent / 100.0)).toInt()
        val taxable = subtotalPaise + serviceChargePaise
        gstPaise = if (gstEnabled) Math.round(taxable * (gstPercent / 100.0)).toInt() else 0
        lastComputedFinalPaise = taxable + gstPaise
    }
"""
import re
content = re.sub(
    r'private fun recomputeTaxesAndTotal.*?lastComputedFinalPaise = taxable \+ gstPaise\n\s*\}',
    recompute_logic.strip(),
    content,
    flags=re.DOTALL
)

# Hide GST row and update footnote
ui_update_logic = """
        tvServiceCharge = findViewById(R.id.tvServiceCharge)
        tvGst = findViewById(R.id.tvGst)
        tvTotal = findViewById(R.id.tvTotal)

        val rowGst = findViewById<View>(R.id.rowGst)
        val tvTaxNote = findViewById<TextView>(R.id.tvTaxNote)
        if (!gstEnabled) {
            rowGst.visibility = View.GONE
            tvTaxNote.text = "Includes 2% service charge"
        } else {
            rowGst.visibility = View.VISIBLE
            val pctInt = gstPercent.toInt()
            tvTaxNote.text = "Includes 2% service charge and ${pctInt}% GST"
        }
"""
content = content.replace(
    "tvServiceCharge = findViewById(R.id.tvServiceCharge)\n        tvGst = findViewById(R.id.tvGst)\n        tvTotal = findViewById(R.id.tvTotal)",
    ui_update_logic.strip()
)

with open("app/src/main/java/com/example/easy_billing/ConfirmPaymentActivity.kt", "w") as f:
    f.write(content)
