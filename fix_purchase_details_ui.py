import re

with open("app/src/main/java/com/example/easy_billing/PurchaseDetailsActivity.kt", "r") as f:
    content = f.read()

old_tax = """        tvTaxBreakdown.text = if (sameState)
            "CGST ${CurrencyHelper.format(this, p.cgstAmount)}  ·  SGST ${CurrencyHelper.format(this, p.sgstAmount)}"
        else
            "IGST ${CurrencyHelper.format(this, p.igstAmount)}\""""
new_tax = """        var taxText = if (sameState)
            "CGST ${CurrencyHelper.format(this, p.cgstAmount)}  ·  SGST ${CurrencyHelper.format(this, p.sgstAmount)}"
        else
            "IGST ${CurrencyHelper.format(this, p.igstAmount)}"
        if (p.cessPaid > 0.0) {
            taxText += "  ·  CESS ${CurrencyHelper.format(this, p.cessPaid)}"
        }
        tvTaxBreakdown.text = taxText"""
content = content.replace(old_tax, new_tax)

with open("app/src/main/java/com/example/easy_billing/PurchaseDetailsActivity.kt", "w") as f:
    f.write(content)
