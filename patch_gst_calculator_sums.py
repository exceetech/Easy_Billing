import re

with open("app/src/main/java/com/example/easy_billing/util/GstBillingCalculator.kt", "r") as f:
    content = f.read()

old_sums = """        val totalIgst  = round2(lines.sumOf { it.igstAmount })
        val totalTax   = round2(totalCgst + totalSgst + totalIgst)
        val grandTotal = round2(netTaxable + totalTax)

        return BillBreakdown(
            gstScheme    = gstScheme,
            supplyType   = supplyType,
            lines        = lines,
            subtotal     = grossSubtotal,   // GROSS (pre-discount) for the Subtotal line
            discount     = effDiscount,
            taxableValue = netTaxable,      // NET taxable — GST base
            totalCgst    = totalCgst,
            totalSgst    = totalSgst,
            totalIgst    = totalIgst,
            totalTax     = totalTax,
            grandTotal   = grandTotal
        )"""

new_sums = """        val totalIgst  = round2(lines.sumOf { it.igstAmount })
        val totalCess  = round2(lines.sumOf { it.cessAmount })
        val totalTax   = round2(totalCgst + totalSgst + totalIgst + totalCess)
        val grandTotal = round2(netTaxable + totalTax)

        return BillBreakdown(
            gstScheme    = gstScheme,
            supplyType   = supplyType,
            lines        = lines,
            subtotal     = grossSubtotal,   // GROSS (pre-discount) for the Subtotal line
            discount     = effDiscount,
            taxableValue = netTaxable,      // NET taxable — GST base
            totalCgst    = totalCgst,
            totalSgst    = totalSgst,
            totalIgst    = totalIgst,
            totalCess    = totalCess,
            totalTax     = totalTax,
            grandTotal   = grandTotal
        )"""
content = content.replace(old_sums, new_sums)

with open("app/src/main/java/com/example/easy_billing/util/GstBillingCalculator.kt", "w") as f:
    f.write(content)
