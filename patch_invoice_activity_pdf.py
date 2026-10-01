import re

with open("app/src/main/java/com/example/easy_billing/InvoiceActivity.kt", "r") as f:
    content = f.read()

old_call = """                InvoicePdfGenerator.generatePdfFromBill(
                    this@InvoiceActivity, bill, billItems, storeInfo,
                    savedInvoice?.gstScheme, savedInvoice, printerLayout
                )"""
new_call = """                InvoicePdfGenerator.generatePdfFromBill(
                    context = this@InvoiceActivity, 
                    bill = bill, 
                    billItems = billItems, 
                    storeInfo = storeInfo,
                    gstScheme = savedInvoice?.gstScheme, 
                    gstInvoice = savedInvoice, 
                    printerLayout = printerLayout,
                    totalCess = lastBreakdown?.totalCess ?: 0.0
                )"""
content = content.replace(old_call, new_call)

with open("app/src/main/java/com/example/easy_billing/InvoiceActivity.kt", "w") as f:
    f.write(content)
