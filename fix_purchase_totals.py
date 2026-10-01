import re

with open("app/src/main/java/com/example/easy_billing/PurchaseActivity.kt", "r") as f:
    content = f.read()

old_compute = """        val cess = etCessPaid.text?.toString()?.toDoubleOrNull() ?: 0.0
        return Totals(taxable, invoice + cess, cgstAmt, sgstAmt, igstAmt)"""
new_compute = """        val cess = etCessPaid.text?.toString()?.toDoubleOrNull() ?: 0.0
        // `invoice` is the sum of `line.invoiceValue`.
        // `PurchaseLineDialog` includes `cessAmount` in `line.invoiceValue`.
        // However, if the user manually overrides `etCessPaid` to a custom value 
        // that differs from the sum of line cesses, we should adjust the total.
        val lineCessSum = viewModel.lines.value.sumOf { it.cessAmount }
        val adjustedInvoice = invoice - lineCessSum + cess
        return Totals(taxable, adjustedInvoice, cgstAmt, sgstAmt, igstAmt)"""
content = content.replace(old_compute, new_compute)

with open("app/src/main/java/com/example/easy_billing/PurchaseActivity.kt", "w") as f:
    f.write(content)
