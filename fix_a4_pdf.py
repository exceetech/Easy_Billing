import re

with open("app/src/main/java/com/example/easy_billing/util/InvoicePdfGenerator.kt", "r") as f:
    content = f.read()

# Fix call site
old_call = """        if (isA4) {
            drawA4InvoicePages(
                context, document, bill, billItems, storeInfo, gstInvoice,
                isComposition, footerMessage, roundOff, currencySymbol, showDiscount
            )"""
new_call = """        if (isA4) {
            drawA4InvoicePages(
                context, document, bill, billItems, storeInfo, gstInvoice,
                isComposition, footerMessage, roundOff, currencySymbol, showDiscount, totalCess
            )"""
content = content.replace(old_call, new_call)

# Fix signature
old_sig = """    private fun drawA4InvoicePages(
        context: Context,
        document: PdfDocument,
        bill: Bill,
        billItems: List<BillItem>,
        storeInfo: StoreInfo?,
        gstInvoice: GstSalesInvoice?,
        isComposition: Boolean,
        footerMessage: String?,
        roundOff: Boolean,
        currencySymbol: String,
        showDiscount: Boolean
    ) {"""
new_sig = """    private fun drawA4InvoicePages(
        context: Context,
        document: PdfDocument,
        bill: Bill,
        billItems: List<BillItem>,
        storeInfo: StoreInfo?,
        gstInvoice: GstSalesInvoice?,
        isComposition: Boolean,
        footerMessage: String?,
        roundOff: Boolean,
        currencySymbol: String,
        showDiscount: Boolean,
        totalCess: Double
    ) {"""
content = content.replace(old_sig, new_sig)

with open("app/src/main/java/com/example/easy_billing/util/InvoicePdfGenerator.kt", "w") as f:
    f.write(content)
