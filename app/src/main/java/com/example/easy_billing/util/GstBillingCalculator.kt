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
        val subtotal: Double,
        val totalCgst: Double,
        val totalSgst: Double,
        val totalIgst: Double,
        val totalTax: Double,
        val grandTotal: Double
    )

    fun calculate(
        items: List<CartItem>,
        gstScheme: String,
        sellerStateCode: String,
        buyerStateCode: String?
    ): BillBreakdown {

        val isComposition = gstScheme.equals(SCHEME_COMPOSITION, ignoreCase = true)

        val supplyType = when {
            isComposition -> "composition"
            // unknown buyer state on a B2C bill defaults to intra-state
            buyerStateCode.isNullOrBlank() -> "intrastate"
            sellerStateCode.isNotBlank() && sellerStateCode == buyerStateCode -> "intrastate"
            else -> "interstate"
        }

        val lines = items.map { ci ->
            val product   = ci.product
            val quantity  = ci.quantity
            val price     = product.price
            val taxable   = round2(price * quantity)

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
                val (cgstPct, sgstPct, igstPct) = if (supplyType == "intrastate") {
                    when {
                        // Use the explicit per-product split.
                        product.cgstPercentage > 0.0 || product.sgstPercentage > 0.0 ->
                            Triple(product.cgstPercentage, product.sgstPercentage, 0.0)

                        // No explicit split saved — fall back to
                        // halving either the IGST rate or the legacy
                        // `defaultGstRate` (treated as the combined
                        // total).
                        else -> {
                            val total = if (product.igstPercentage > 0.0)
                                product.igstPercentage
                            else
                                product.defaultGstRate
                            val half = total / 2.0
                            Triple(half, half, 0.0)
                        }
                    }
                } else {
                    val igst = when {
                        product.igstPercentage > 0.0 -> product.igstPercentage
                        // Inter-state IGST = sum of the intra-state
                        // CGST + SGST split.
                        (product.cgstPercentage + product.sgstPercentage) > 0.0 ->
                            product.cgstPercentage + product.sgstPercentage
                        else -> product.defaultGstRate
                    }
                    Triple(0.0, 0.0, igst)
                }

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

        val subtotal   = round2(lines.sumOf { it.taxableAmount })
        val totalCgst  = round2(lines.sumOf { it.cgstAmount })
        val totalSgst  = round2(lines.sumOf { it.sgstAmount })
        val totalIgst  = round2(lines.sumOf { it.igstAmount })
        val totalTax   = round2(totalCgst + totalSgst + totalIgst)
        val grandTotal = round2(subtotal + totalTax)

        return BillBreakdown(
            gstScheme   = gstScheme,
            supplyType  = supplyType,
            lines       = lines,
            subtotal    = subtotal,
            totalCgst   = totalCgst,
            totalSgst   = totalSgst,
            totalIgst   = totalIgst,
            totalTax    = totalTax,
            grandTotal  = grandTotal
        )
    }

    /**
     * Convert the calculator's per-line view into Room rows ready
     * to be inserted via [com.example.easy_billing.db.GstSalesInvoiceItemDao].
     * Caller fills in `gstInvoiceId` after the parent insert.
     */
    fun toInvoiceItems(
        gstInvoiceId: Int,
        breakdown: BillBreakdown
    ): List<GstSalesInvoiceItem> = breakdown.lines.map { l ->
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
            netValue             = l.netValue
        )
    }

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
}
