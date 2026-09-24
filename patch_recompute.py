import re
with open("app/src/main/java/com/example/easy_billing/ConfirmPaymentActivity.kt", "r") as f:
    content = f.read()

recompute_logic = """
    private fun recomputeLocalBreakdown(newSubtotalPaise: Int) {
        subtotalPaise = newSubtotalPaise
        serviceChargePaise = Math.round(subtotalPaise * (serviceChargePercent / 100.0)).toInt()
        val taxable = subtotalPaise + serviceChargePaise
        gstPaise = if (gstEnabled) Math.round(taxable * (gstPercent / 100.0)).toInt() else 0
        lastComputedFinalPaise = taxable + gstPaise
    }
"""

content = re.sub(
    r'private fun recomputeLocalBreakdown.*?lastComputedFinalPaise = taxable \+ gstPaise\n\s*\}',
    recompute_logic.strip(),
    content,
    flags=re.DOTALL
)

with open("app/src/main/java/com/example/easy_billing/ConfirmPaymentActivity.kt", "w") as f:
    f.write(content)
