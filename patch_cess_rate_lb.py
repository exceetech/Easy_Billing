import re

with open("app/src/main/java/com/example/easy_billing/util/GstBillingCalculator.kt", "r") as f:
    content = f.read()

# 1. Add cessPercentage to LineBreakdown
old_lb = """        val igstPercentage: Double,
        val cgstAmount: Double,"""
new_lb = """        val igstPercentage: Double,
        val cessPercentage: Double,
        val cgstAmount: Double,"""
content = content.replace(old_lb, new_lb)

# 2. Update lines instantiation
old_lines_comp = """                    sgstPercentage = 0.0,
                    igstPercentage = 0.0,
                    cgstAmount     = 0.0,"""
new_lines_comp = """                    sgstPercentage = 0.0,
                    igstPercentage = 0.0,
                    cessPercentage = 0.0,
                    cgstAmount     = 0.0,"""
content = content.replace(old_lines_comp, new_lines_comp)

old_lines_norm = """                    sgstPercentage = sgstPct,
                    igstPercentage = igstPct,
                    cgstAmount     = cgstAmt,"""
new_lines_norm = """                    sgstPercentage = sgstPct,
                    igstPercentage = igstPct,
                    cessPercentage = cessPct,
                    cgstAmount     = cgstAmt,"""
content = content.replace(old_lines_norm, new_lines_norm)

# 3. Update toInvoiceItems
old_to = """            cessRate             = en.cessRate,
            cessAmount           = en.cessAmount,"""
new_to = """            cessRate             = l.cessPercentage,
            cessAmount           = l.cessAmount,"""
content = content.replace(old_to, new_to)

with open("app/src/main/java/com/example/easy_billing/util/GstBillingCalculator.kt", "w") as f:
    f.write(content)
