import re

with open("app/src/main/java/com/example/easy_billing/util/GstBillingCalculator.kt", "r") as f:
    content = f.read()

# 1. Update LineBreakdown
old_lb = """        val cgstAmount: Double,
        val sgstAmount: Double,
        val igstAmount: Double,
        val netValue: Double"""
new_lb = """        val cgstAmount: Double,
        val sgstAmount: Double,
        val igstAmount: Double,
        val cessAmount: Double,
        val netValue: Double"""
content = content.replace(old_lb, new_lb)

# 2. Update BillBreakdown
old_bb = """        val totalSgst: Double,
        val totalIgst: Double,
        val totalTax: Double,"""
new_bb = """        val totalSgst: Double,
        val totalIgst: Double,
        val totalCess: Double,
        val totalTax: Double,"""
content = content.replace(old_bb, new_bb)

# 3. Update grossTaxables calculation
old_gross = """            if (isInclusive) {
                // Backward calculation guarantees exact MRP totals without penny loss
                val net = round2(product.price * ci.quantity - ci.discountAmount)
                val cgstAmt = round2(net * cgstPct / (100.0 + totalTaxPct))
                val sgstAmt = round2(net * sgstPct / (100.0 + totalTaxPct))
                val igstAmt = round2(net * igstPct / (100.0 + totalTaxPct))
                round2(net - cgstAmt - sgstAmt - igstAmt)
            } else {"""
            
new_gross = """            if (isInclusive) {
                // Backward calculation guarantees exact MRP totals without penny loss
                val net = round2(product.price * ci.quantity - ci.discountAmount)
                val cessPct = product.cessRate
                val totalTaxWithCess = totalTaxPct + cessPct
                val cgstAmt = round2(net * cgstPct / (100.0 + totalTaxWithCess))
                val sgstAmt = round2(net * sgstPct / (100.0 + totalTaxWithCess))
                val igstAmt = round2(net * igstPct / (100.0 + totalTaxWithCess))
                val cessAmt = round2(net * cessPct / (100.0 + totalTaxWithCess))
                round2(net - cgstAmt - sgstAmt - igstAmt - cessAmt)
            } else {"""
content = content.replace(old_gross, new_gross)

# 4. Update lines generation
old_lines = """            if (isComposition) {
                LineBreakdown(
                    productId      = product.id,
                    productName    = product.name,
                    variantName    = product.variant,
                    hsnCode        = product.hsnCode ?: "",
                    quantity       = quantity,
                    sellingPrice   = price,
                    taxableAmount  = taxable,
                    cgstPercentage = 0.0,
                    sgstPercentage = 0.0,
                    igstPercentage = 0.0,
                    cgstAmount     = 0.0,
                    sgstAmount     = 0.0,
                    igstAmount     = 0.0,
                    netValue       = taxable
                )
            } else {
                // Normal GST — pick the rate stored on the product
                // *for the relevant supply type*. The product row
                // already keeps CGST + SGST as a split (e.g. 6 + 6
                // for a 12 % product) and IGST as the combined
                // inter-state rate (e.g. 12). They must NOT be summed
                // — doing that double-counts the same tax and pushes
                // a 12 % bill up to 24 %.
                val (cgstPct, sgstPct, igstPct) = itemTaxRates[idx]

                val cgstAmt = round2(taxable * cgstPct / 100.0)
                val sgstAmt = round2(taxable * sgstPct / 100.0)
                val igstAmt = round2(taxable * igstPct / 100.0)
                val net     = round2(taxable + cgstAmt + sgstAmt + igstAmt)

                LineBreakdown(
                    productId      = product.id,
                    productName    = product.name,
                    variantName    = product.variant,
                    hsnCode        = product.hsnCode ?: "",
                    quantity       = quantity,
                    sellingPrice   = price,
                    taxableAmount  = taxable,
                    cgstPercentage = cgstPct,
                    sgstPercentage = sgstPct,
                    igstPercentage = igstPct,
                    cgstAmount     = cgstAmt,
                    sgstAmount     = sgstAmt,
                    igstAmount     = igstAmt,
                    netValue       = net
                )
            }"""

new_lines = """            if (isComposition) {
                LineBreakdown(
                    productId      = product.id,
                    productName    = product.name,
                    variantName    = product.variant,
                    hsnCode        = product.hsnCode ?: "",
                    quantity       = quantity,
                    sellingPrice   = price,
                    taxableAmount  = taxable,
                    cgstPercentage = 0.0,
                    sgstPercentage = 0.0,
                    igstPercentage = 0.0,
                    cgstAmount     = 0.0,
                    sgstAmount     = 0.0,
                    igstAmount     = 0.0,
                    cessAmount     = 0.0,
                    netValue       = taxable
                )
            } else {
                // Normal GST — pick the rate stored on the product
                // *for the relevant supply type*. The product row
                // already keeps CGST + SGST as a split (e.g. 6 + 6
                // for a 12 % product) and IGST as the combined
                // inter-state rate (e.g. 12). They must NOT be summed
                // — doing that double-counts the same tax and pushes
                // a 12 % bill up to 24 %.
                val (cgstPct, sgstPct, igstPct) = itemTaxRates[idx]
                val cessPct = product.cessRate

                val cgstAmt = round2(taxable * cgstPct / 100.0)
                val sgstAmt = round2(taxable * sgstPct / 100.0)
                val igstAmt = round2(taxable * igstPct / 100.0)
                val cessAmt = round2(taxable * cessPct / 100.0)
                val net     = round2(taxable + cgstAmt + sgstAmt + igstAmt + cessAmt)

                LineBreakdown(
                    productId      = product.id,
                    productName    = product.name,
                    variantName    = product.variant,
                    hsnCode        = product.hsnCode ?: "",
                    quantity       = quantity,
                    sellingPrice   = price,
                    taxableAmount  = taxable,
                    cgstPercentage = cgstPct,
                    sgstPercentage = sgstPct,
                    igstPercentage = igstPct,
                    cgstAmount     = cgstAmt,
                    sgstAmount     = sgstAmt,
                    igstAmount     = igstAmt,
                    cessAmount     = cessAmt,
                    netValue       = net
                )
            }"""
content = content.replace(old_lines, new_lines)

# 5. Update final sums
old_sums = """        val totIgst = round2(lines.sumOf { it.igstAmount })
        val totTax  = round2(totCgst + totSgst + totIgst)
        val grand   = round2(netTaxable + totTax)

        return BillBreakdown(
            gstScheme    = gstScheme,
            supplyType   = supplyType,
            lines        = lines,
            subtotal     = grossSubtotal,
            discount     = effDiscount,
            taxableValue = netTaxable,
            totalCgst    = totCgst,
            totalSgst    = totSgst,
            totalIgst    = totIgst,
            totalTax     = totTax,
            grandTotal   = grand
        )"""
new_sums = """        val totIgst = round2(lines.sumOf { it.igstAmount })
        val totCess = round2(lines.sumOf { it.cessAmount })
        val totTax  = round2(totCgst + totSgst + totIgst + totCess)
        val grand   = round2(netTaxable + totTax)

        return BillBreakdown(
            gstScheme    = gstScheme,
            supplyType   = supplyType,
            lines        = lines,
            subtotal     = grossSubtotal,
            discount     = effDiscount,
            taxableValue = netTaxable,
            totalCgst    = totCgst,
            totalSgst    = totSgst,
            totalIgst    = totIgst,
            totalCess    = totCess,
            totalTax     = totTax,
            grandTotal   = grand
        )"""
content = content.replace(old_sums, new_sums)

with open("app/src/main/java/com/example/easy_billing/util/GstBillingCalculator.kt", "w") as f:
    f.write(content)
