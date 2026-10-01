import re

with open("app/src/main/java/com/example/easy_billing/util/InvoicePdfGenerator.kt", "r") as f:
    content = f.read()

# 1. Update signature
old_sig = """        gstInvoice: GstSalesInvoice? = null,
        printerLayout: String = "80mm",
        // Additive, defaults to true so every existing call site (print
        // button flows) behaves byte-for-byte as before. Only the new
        // "send to customer" flow passes false, to get the saved File
        // back without popping the system print dialog.
        printAfterSave: Boolean = true
    ): File {"""
new_sig = """        gstInvoice: GstSalesInvoice? = null,
        printerLayout: String = "80mm",
        totalCess: Double = 0.0,
        // Additive, defaults to true so every existing call site (print
        // button flows) behaves byte-for-byte as before. Only the new
        // "send to customer" flow passes false, to get the saved File
        // back without popping the system print dialog.
        printAfterSave: Boolean = true
    ): File {"""
content = content.replace(old_sig, new_sig)

# 2. 80mm totals
old_80mm_tax = """        val totalTax = bill.cgstAmount + bill.sgstAmount + bill.igstAmount

        leftText(if (isComposition) context.getString(R.string.invoice_pdf_sub_total_label) else context.getString(R.string.invoice_pdf_taxable_amount_label), 14f)
        rightText("$currencySymbol%.2f".format(taxable), 14f)
        y += 22

        if (!isComposition) {
            // Intra-state → CGST + SGST.  Inter-state → IGST only.
            if (bill.igstAmount > 0.0) {
                leftText(context.getString(R.string.invoice_pdf_igst_label), 14f)
                rightText("$currencySymbol%.2f".format(bill.igstAmount), 14f)
                y += 22
            } else {
                leftText(context.getString(R.string.invoice_pdf_cgst_label), 14f)
                rightText("$currencySymbol%.2f".format(bill.cgstAmount), 14f)
                y += 22
                leftText(context.getString(R.string.invoice_pdf_sgst_label), 14f)
                rightText("$currencySymbol%.2f".format(bill.sgstAmount), 14f)
                y += 22
            }
        }"""
new_80mm_tax = """        val totalTax = bill.cgstAmount + bill.sgstAmount + bill.igstAmount + totalCess

        leftText(if (isComposition) context.getString(R.string.invoice_pdf_sub_total_label) else context.getString(R.string.invoice_pdf_taxable_amount_label), 14f)
        rightText("$currencySymbol%.2f".format(taxable), 14f)
        y += 22

        if (!isComposition) {
            // Intra-state → CGST + SGST.  Inter-state → IGST only.
            if (bill.igstAmount > 0.0) {
                leftText(context.getString(R.string.invoice_pdf_igst_label), 14f)
                rightText("$currencySymbol%.2f".format(bill.igstAmount), 14f)
                y += 22
            } else {
                leftText(context.getString(R.string.invoice_pdf_cgst_label), 14f)
                rightText("$currencySymbol%.2f".format(bill.cgstAmount), 14f)
                y += 22
                leftText(context.getString(R.string.invoice_pdf_sgst_label), 14f)
                rightText("$currencySymbol%.2f".format(bill.sgstAmount), 14f)
                y += 22
            }
            if (totalCess > 0.0) {
                leftText("Cess", 14f)
                rightText("$currencySymbol%.2f".format(totalCess), 14f)
                y += 22
            }
        }"""
content = content.replace(old_80mm_tax, new_80mm_tax)

# 3. A4 totals
old_a4_tax = """        val totalTax = bill.cgstAmount + bill.sgstAmount + bill.igstAmount
        var finalTotal = bill.total"""
new_a4_tax = """        val totalTax = bill.cgstAmount + bill.sgstAmount + bill.igstAmount + totalCess
        var finalTotal = bill.total"""
content = content.replace(old_a4_tax, new_a4_tax)

old_a4_rows = """            if (bill.igstAmount > 0.0) {
                totalsRow(context.getString(R.string.invoice_pdf_igst_label), "$currencySymbol%.2f".format(bill.igstAmount))
            } else {
                totalsRow(context.getString(R.string.invoice_pdf_cgst_label), "$currencySymbol%.2f".format(bill.cgstAmount))
                totalsRow(context.getString(R.string.invoice_pdf_sgst_label), "$currencySymbol%.2f".format(bill.sgstAmount))
            }
        }"""
new_a4_rows = """            if (bill.igstAmount > 0.0) {
                totalsRow(context.getString(R.string.invoice_pdf_igst_label), "$currencySymbol%.2f".format(bill.igstAmount))
            } else {
                totalsRow(context.getString(R.string.invoice_pdf_cgst_label), "$currencySymbol%.2f".format(bill.cgstAmount))
                totalsRow(context.getString(R.string.invoice_pdf_sgst_label), "$currencySymbol%.2f".format(bill.sgstAmount))
            }
            if (totalCess > 0.0) {
                totalsRow("Cess", "$currencySymbol%.2f".format(totalCess))
            }
        }"""
content = content.replace(old_a4_rows, new_a4_rows)

with open("app/src/main/java/com/example/easy_billing/util/InvoicePdfGenerator.kt", "w") as f:
    f.write(content)
