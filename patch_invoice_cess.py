import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old_invoice_val = """    private fun invoiceValue(
        taxable: Double,
        cgst: Double,
        sgst: Double,
        igst: Double,
        invoiceState: String
    ): Double {
        val sameState = isIntraState(invoiceState)
        return if (sameState) {
            taxable + (taxable * cgst / 100.0) + (taxable * sgst / 100.0)
        } else {
            taxable + (taxable * igst / 100.0)
        }
    }"""

new_invoice_val = """    private fun invoiceValue(
        taxable: Double,
        cgst: Double,
        sgst: Double,
        igst: Double,
        cessAmount: Double,
        invoiceState: String
    ): Double {
        val sameState = isIntraState(invoiceState)
        return if (sameState) {
            taxable + (taxable * cgst / 100.0) + (taxable * sgst / 100.0) + cessAmount
        } else {
            taxable + (taxable * igst / 100.0) + cessAmount
        }
    }"""

content = content.replace(old_invoice_val, new_invoice_val)

old_for = """        val invoiceValueFor = { taxable: Double ->
            invoiceValue(
                taxable = taxable,
                cgst = etPCgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                sgst = etPSgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                igst = etPIgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                invoiceState = invoiceState
            )
        }"""
        
new_for = """        val invoiceValueFor = { taxable: Double ->
            val cessAmt = etCessAmountPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            invoiceValue(
                taxable = taxable,
                cgst = etPCgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                sgst = etPSgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                igst = etPIgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                cessAmount = cessAmt,
                invoiceState = invoiceState
            )
        }"""

content = content.replace(old_for, new_for)

old_list = "listOf(etTax, etPCgst, etPSgst, etPIgst).forEach { it.addTextChangedListener { recomputeInvoice() } }"
new_list = "listOf(etTax, etPCgst, etPSgst, etPIgst, etCessAmountPurchase).forEach { it.addTextChangedListener { recomputeInvoice() } }"

content = content.replace(old_list, new_list)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
