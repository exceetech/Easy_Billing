package com.example.easy_billing.util

import com.example.easy_billing.db.GstSalesInvoiceItem
import com.example.easy_billing.model.CartItem
import kotlin.math.round

/**
 * GST-aware billing calculator.
 *
 * Single entry point — [calculate] — that takes the cart, the
 * shop's GST scheme, and the buyer state and returns a fully
 * resolved breakdown (per-line + totals + ready-to-persist
 * [GstSalesInvoiceItem] rows).
 *
 *   • Composition Scheme — tax stays bundled into the selling
 *     price. No CGST/SGST/IGST is shown to the buyer; totals
 *     equal the simple sum of `quantity × sellingPrice` on every
 *     line.
 *
 *   • Normal GST Scheme  — tax is computed *per product* using
 *     the rates stored on the product row
 *     (`cgstPercentage`/`sgstPercentage`/`igstPercentage`,
 *     falling back to `defaultGstRate` when product columns are
 *     zero). Intra-state sales charge CGST + SGST, inter-state
 *     sales charge IGST only — never both.
 *
 *   • Selling prices in the catalogue are treated as
 *     *tax-exclusive* (`taxableAmount = quantity × sellingPrice`).
 *     This matches the pre-existing [GstEngine.calculateGstSplit]
 *     contract, so reports written via either path stay aligned.
 */
object GstBillingCalculator {

    const val SCHEME_COMPOSITION = "Composition Scheme"
    const val SCHEME_NORMAL      = "Normal GST Scheme"

    /** Per-line breakdown — kept open for the InvoiceAdapter / PDF generator. */
    data class LineBreakdown(
        val productId: Int,
        val productName: String,
        val variantName: String?,
        val hsnCode: String,
        val quantity: Double,
        val sellingPrice: Double,
        val taxableAmount: Double,
        val cgstPercentage: Double,
        val sgstPercentage: Double,
        val igstPercentage: Double,
        val cgstAmount: Double,
        val sgstAmount: Double,
        val igstAmount: Double,
        val netValue: Double
    )

    /** Aggregated bill — what the UI / persistence layer ultimately uses. */
    data class BillBreakdown(
        val gstScheme: String,
        val supplyType: String,            // "intrastate" / "interstate" / "composition"
        val lines: List<LineBreakdown>,
        /** GROSS taxable, before any bill discount (the "Subtotal" line). */
        val subtotal: Double,
        /** Bill discount applied pre-tax (reduces the taxable value). */
        val discount: Double,
        /** NET taxable = subtotal − discount (the base GST is charged on). */
        val taxableValue: Double,
        val totalCgst: Double,
        val totalSgst: Double,
        val totalIgst: Double,
        val totalTax: Double,
        val grandTotal: Double
    )

    /**
     * @param billDiscount  A bill-level discount applied PRE-TAX: it is spread
     *   across the line items in proportion to their taxable value, lowering
     *   the taxable amount on which GST is charged. This is the GST-correct
     *   treatment (Section 15): it reduces both the taxable value and the tax,
     *   so what you collect matches what you remit and returns / GSTR-1 stay
     *   consistent automatically. Defaults to 0.
     */
    fun calculate(
        items: List<CartItem>,
        gstScheme: String,
        sellerStateCode: String,
        buyerStateCode: String?,
        billDiscount: Double = 0.0
    ): BillBreakdown {

        val isComposition = gstScheme.equals(SCHEME_COMPOSITION, ignoreCase = true)

        val supplyType = when {
            isComposition -> "composition"
            // unknown buyer state on a B2C bill defaults to intra-state
            buyerStateCode.isNullOrBlank() -> "intrastate"
            sellerStateCode.isNotBlank() && sellerStateCode == buyerStateCode -> "intrastate"
            else -> "interstate"
        }

        // ── Pre-tax discount allocation ─────────────────────────────────────
        // Gross taxable per line (price × qty); spread the bill discount over
        // the lines in proportion to that gross value. Last line absorbs the
        // rounding remainder so Σ(lineDiscount) == effDiscount exactly.
        
        // Determine tax rates per item first so we can back-calculate base prices for inclusive items
        val itemTaxRates = items.map { ci ->
            val product = ci.product
            if (isComposition) {
                Triple(0.0, 0.0, 0.0)
            } else if (supplyType == "intrastate") {
                when {
                    product.cgstPercentage > 0.0 || product.sgstPercentage > 0.0 ->
                        Triple(product.cgstPercentage, product.sgstPercentage, 0.0)
                    else -> {
                        val total = if (product.igstPercentage > 0.0) product.igstPercentage else product.defaultGstRate
                        val half = total / 2.0
                        Triple(half, half, 0.0)
                    }
                }
            } else {
                val igst = when {
                    product.igstPercentage > 0.0 -> product.igstPercentage
                    (product.cgstPercentage + product.sgstPercentage) > 0.0 -> product.cgstPercentage + product.sgstPercentage
                    else -> product.defaultGstRate
                }
                Triple(0.0, 0.0, igst)
            }
        }

        val basePrices = items.mapIndexed { idx, ci ->
            val product = ci.product
            val (cgstPct, sgstPct, igstPct) = itemTaxRates[idx]
            val totalTaxPct = cgstPct + sgstPct + igstPct
            if (product.isTaxInclusive && !isComposition) {
                product.price / (1.0 + totalTaxPct / 100.0)
            } else {
                product.price
            }
        }

        val grossTaxables = items.mapIndexed { idx, ci -> 
            val product = ci.product
            val (cgstPct, sgstPct, igstPct) = itemTaxRates[idx]
            val totalTaxPct = cgstPct + sgstPct + igstPct
            val isInclusive = product.isTaxInclusive && !isComposition

            if (isInclusive) {
                // Backward calculation guarantees exact MRP totals without penny loss
                val net = round2(product.price * ci.quantity)
                val cgstAmt = round2(net * cgstPct / (100.0 + totalTaxPct))
                val sgstAmt = round2(net * sgstPct / (100.0 + totalTaxPct))
                val igstAmt = round2(net * igstPct / (100.0 + totalTaxPct))
                round2(net - cgstAmt - sgstAmt - igstAmt)
            } else {
                round2(basePrices[idx] * ci.quantity) 
            }
        }
        val grossSubtotal = round2(grossTaxables.sum())
        val effDiscount   = round2(billDiscount.coerceIn(0.0, grossSubtotal))

        val lineDiscounts = DoubleArray(items.size)
        if (effDiscount > 0.0 && grossSubtotal > 0.0) {
            var allocated = 0.0
            for (i in items.indices) {
                lineDiscounts[i] = if (i == items.lastIndex) {
                    round2(effDiscount - allocated)
                } else {
                    val d = round2(effDiscount * grossTaxables[i] / grossSubtotal)
                    allocated = round2(allocated + d)
                    d
                }
            }
        }

        val lines = items.mapIndexed { idx, ci ->
            val product   = ci.product
            val quantity  = ci.quantity
            val price     = basePrices[idx]
            // NET taxable: gross minus this line's share of the bill discount.
            val taxable   = round2((grossTaxables[idx] - lineDiscounts[idx]).coerceAtLeast(0.0))

            if (isComposition) {
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
            }
        }

        val netTaxable = round2(lines.sumOf { it.taxableAmount })   // = gross − discount
        val totalCgst  = round2(lines.sumOf { it.cgstAmount })
        val totalSgst  = round2(lines.sumOf { it.sgstAmount })
        val totalIgst  = round2(lines.sumOf { it.igstAmount })
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
        )
    }

    /**
     * Convert the calculator's per-line view into Room rows ready
     * to be inserted via [com.example.easy_billing.db.GstSalesInvoiceItemDao].
     * Caller fills in `gstInvoiceId` after the parent insert.
     *
     * Pass [enrichments] (parallel to breakdown.lines) to populate
     * the GSTR-1 product master fields: cessRate, cessAmount, uqc,
     * hsnDescription. Defaults to all-zero / null when omitted.
     */
    fun toInvoiceItems(
        gstInvoiceId: Int,
        breakdown: BillBreakdown,
        enrichments: List<GstEngine.SalesRecordEnrichment> = emptyList()
    ): List<GstSalesInvoiceItem> = breakdown.lines.mapIndexed { idx, l ->
        val en = enrichments.getOrNull(idx) ?: GstEngine.SalesRecordEnrichment()
        GstSalesInvoiceItem(
            gstInvoiceId         = gstInvoiceId,
            productId            = l.productId,
            productName          = l.productName,
            variantName          = l.variantName,
            hsnCode              = l.hsnCode,
            quantity             = l.quantity,
            sellingPrice         = l.sellingPrice,
            taxableAmount        = l.taxableAmount,
            salesCgstPercentage  = l.cgstPercentage,
            salesSgstPercentage  = l.sgstPercentage,
            salesIgstPercentage  = l.igstPercentage,
            cgstAmount           = l.cgstAmount,
            sgstAmount           = l.sgstAmount,
            igstAmount           = l.igstAmount,
            netValue             = l.netValue,
            cessRate             = en.cessRate,
            cessAmount           = en.cessAmount,
            uqc                  = en.uqc,
            hsnDescription       = en.hsnDescription,
            supplyClassification = en.supplyClassification
        )
    }

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0

    /** Public version for callers outside this class (e.g. InvoiceActivity). */
    fun round2Pub(value: Double): Double = round2(value)
}
