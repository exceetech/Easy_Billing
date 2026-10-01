import re

with open("app/src/main/java/com/example/easy_billing/BillDetailsActivity.kt", "r") as f:
    content = f.read()

old_call = """                val printerLayout = db.billingSettingsDao().get()?.printerLayout ?: "80mm"

                InvoicePdfGenerator.generatePdfFromBill(
                    context = this@BillDetailsActivity,
                    bill = printBill,
                    billItems = printItems,
                    storeInfo = storeInfo,
                    gstScheme = savedInvoice?.gstScheme,
                    gstInvoice = savedInvoice,
                    printerLayout = printerLayout,
                    printAfterSave = printAfterSave
                )"""
new_call = """                val printerLayout = db.billingSettingsDao().get()?.printerLayout ?: "80mm"
                
                val totalCess = if (savedInvoice != null) {
                    db.gstSalesInvoiceItemDao().getByInvoice(savedInvoice.id).sumOf { it.cessAmount }
                } else {
                    0.0
                }

                InvoicePdfGenerator.generatePdfFromBill(
                    context = this@BillDetailsActivity,
                    bill = printBill,
                    billItems = printItems,
                    storeInfo = storeInfo,
                    gstScheme = savedInvoice?.gstScheme,
                    gstInvoice = savedInvoice,
                    printerLayout = printerLayout,
                    totalCess = totalCess,
                    printAfterSave = printAfterSave
                )"""
content = content.replace(old_call, new_call)

with open("app/src/main/java/com/example/easy_billing/BillDetailsActivity.kt", "w") as f:
    f.write(content)
