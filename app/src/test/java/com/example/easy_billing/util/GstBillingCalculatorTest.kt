package com.example.easy_billing.util

import com.example.easy_billing.db.Product
import com.example.easy_billing.model.CartItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [GstBillingCalculator] — the checkout/GST math behind
 * InvoiceActivity.saveBill(). This is the single most financially critical
 * piece of logic in the app (every bill's tax and total flow through it) and
 * previously had zero test coverage. No Android dependencies, runs under
 * `./gradlew test`.
 *
 * Expected values below are hand-computed from the calculator's own
 * documented algorithm (see GstBillingCalculator.kt's inline comments), not
 * copied from its output — so these tests actually pin down correctness,
 * not just "whatever the code currently does."
 */
class GstBillingCalculatorTest {

    /** Minimal Product fixture — only the fields calculate() reads are meaningful. */
    private fun product(
        id: Int = 1,
        price: Double,
        cgst: Double = 0.0,
        sgst: Double = 0.0,
        igst: Double = 0.0,
        defaultGstRate: Double = 0.0,
        isTaxInclusive: Boolean = false
    ) = Product(
        id = id,
        name = "Test Product",
        variant = null,
        unit = "piece",
        price = price,
        trackInventory = false,
        cgstPercentage = cgst,
        sgstPercentage = sgst,
        igstPercentage = igst,
        defaultGstRate = defaultGstRate,
        isTaxInclusive = isTaxInclusive
    )

    @Test
    fun intrastate_splitsEquallyIntoCgstAndSgst() {
        // Seller and buyer in the same state — 12% (6+6) product, qty 2.
        val item = CartItem(product(price = 100.0, cgst = 6.0, sgst = 6.0), quantity = 2.0)
        val bill = GstBillingCalculator.calculate(
            items = listOf(item),
            gstScheme = GstBillingCalculator.SCHEME_NORMAL,
            sellerStateCode = "27",
            buyerStateCode = "27"
        )
        assertEquals("intrastate", bill.supplyType)
        assertEquals(200.0, bill.taxableValue, 0.0)
        assertEquals(12.0, bill.totalCgst, 0.0)
        assertEquals(12.0, bill.totalSgst, 0.0)
        assertEquals(0.0, bill.totalIgst, 0.0)
        assertEquals(224.0, bill.grandTotal, 0.0)
    }

    @Test
    fun interstate_chargesIgstOnly_neverCgstSgstToo() {
        // Same product/total as the intrastate case above, different buyer state.
        // Total tax must match the intrastate case exactly (12%) — only the
        // split changes. Charging CGST+SGST+IGST together would double-tax.
        val item = CartItem(product(price = 100.0, cgst = 6.0, sgst = 6.0), quantity = 2.0)
        val bill = GstBillingCalculator.calculate(
            items = listOf(item),
            gstScheme = GstBillingCalculator.SCHEME_NORMAL,
            sellerStateCode = "27",
            buyerStateCode = "29"
        )
        assertEquals("interstate", bill.supplyType)
        assertEquals(0.0, bill.totalCgst, 0.0)
        assertEquals(0.0, bill.totalSgst, 0.0)
        assertEquals(24.0, bill.totalIgst, 0.0)
        assertEquals(224.0, bill.grandTotal, 0.0)
    }

    @Test
    fun compositionScheme_noTaxShownToBuyer() {
        // Composition-scheme tax stays bundled in the price — the bill shows
        // zero CGST/SGST/IGST regardless of buyer state or product tax fields.
        val item = CartItem(product(price = 100.0, cgst = 6.0, sgst = 6.0), quantity = 3.0)
        val bill = GstBillingCalculator.calculate(
            items = listOf(item),
            gstScheme = GstBillingCalculator.SCHEME_COMPOSITION,
            sellerStateCode = "27",
            buyerStateCode = "29"
        )
        assertEquals("composition", bill.supplyType)
        assertEquals(0.0, bill.totalTax, 0.0)
        assertEquals(300.0, bill.taxableValue, 0.0)
        assertEquals(300.0, bill.grandTotal, 0.0)
    }

    @Test
    fun taxInclusivePrice_backCalculatesWithoutPennyLoss() {
        // Sticker price 112 already includes 12% GST (6+6). The base
        // (tax-exclusive) price must back out to exactly 100, and the
        // grand total must round-trip back to the original 112 sticker price.
        val item = CartItem(
            product(price = 112.0, cgst = 6.0, sgst = 6.0, isTaxInclusive = true),
            quantity = 1.0
        )
        val bill = GstBillingCalculator.calculate(
            items = listOf(item),
            gstScheme = GstBillingCalculator.SCHEME_NORMAL,
            sellerStateCode = "27",
            buyerStateCode = "27"
        )
        assertEquals(100.0, bill.lines[0].sellingPrice, 0.0)
        assertEquals(100.0, bill.taxableValue, 0.0)
        assertEquals(6.0, bill.totalCgst, 0.0)
        assertEquals(6.0, bill.totalSgst, 0.0)
        assertEquals(112.0, bill.grandTotal, 0.0)
    }

    @Test
    fun fallsBackToDefaultGstRate_whenProductHasNoOwnRates() {
        // Product with cgst=sgst=igst=0 but a defaultGstRate of 18% — the
        // intrastate path must split that fallback rate 9/9, not treat the
        // product as tax-free.
        val item = CartItem(product(price = 100.0, defaultGstRate = 18.0), quantity = 1.0)
        val bill = GstBillingCalculator.calculate(
            items = listOf(item),
            gstScheme = GstBillingCalculator.SCHEME_NORMAL,
            sellerStateCode = "27",
            buyerStateCode = "27"
        )
        assertEquals(9.0, bill.totalCgst, 0.0)
        assertEquals(9.0, bill.totalSgst, 0.0)
        assertEquals(118.0, bill.grandTotal, 0.0)
    }

    @Test
    fun nullBuyerState_defaultsToIntrastate() {
        // Anonymous/quick B2C sale — no buyer state entered at all. Must
        // default to intrastate (charge CGST+SGST), not crash or misfile
        // as interstate just because the buyer's state is unknown.
        val item = CartItem(product(price = 50.0, cgst = 6.0, sgst = 6.0), quantity = 1.0)
        val bill = GstBillingCalculator.calculate(
            items = listOf(item),
            gstScheme = GstBillingCalculator.SCHEME_NORMAL,
            sellerStateCode = "27",
            buyerStateCode = null
        )
        assertEquals("intrastate", bill.supplyType)
        assertEquals(3.0, bill.totalCgst, 0.0)
        assertEquals(3.0, bill.totalSgst, 0.0)
    }

    @Test
    fun lineDiscount_reducesTaxableAmountBeforeTax() {
        // A flat per-line discount (e.g. a line-item markdown) is pre-tax:
        // 200 gross minus a flat 20 discount = 180 taxable, THEN GST applies
        // to the 180 — not to the original 200.
        val item = CartItem(
            product(price = 100.0, cgst = 6.0, sgst = 6.0), quantity = 2.0, discountAmount = 20.0
        )
        val bill = GstBillingCalculator.calculate(
            items = listOf(item),
            gstScheme = GstBillingCalculator.SCHEME_NORMAL,
            sellerStateCode = "27",
            buyerStateCode = "27"
        )
        assertEquals(180.0, bill.taxableValue, 0.0)
        assertEquals(201.6, bill.grandTotal, 0.0)
    }

    @Test
    fun billDiscount_isProratedAcrossLines_andSumsExactly() {
        // A bill-level discount of 50 spread proportionally across two lines
        // (100 and 200 gross, 1:2 ratio) doesn't divide evenly — 16.666... and
        // 33.333.... The last line must absorb the rounding remainder so the
        // two line discounts sum to EXACTLY 50.00, not 49.99 or 50.01 (which
        // would silently under/over-charge tax and break GSTR-1 reconciliation).
        val lineA = CartItem(product(id = 1, price = 100.0, cgst = 6.0, sgst = 6.0), quantity = 1.0)
        val lineB = CartItem(product(id = 2, price = 200.0, cgst = 6.0, sgst = 6.0), quantity = 1.0)
        val bill = GstBillingCalculator.calculate(
            items = listOf(lineA, lineB),
            gstScheme = GstBillingCalculator.SCHEME_NORMAL,
            sellerStateCode = "27",
            buyerStateCode = "27",
            billDiscount = 50.0
        )
        assertEquals(300.0, bill.subtotal, 0.0)   // gross, pre-discount
        assertEquals(50.0, bill.discount, 0.0)
        assertEquals(250.0, bill.taxableValue, 0.0)
        assertEquals(16.67, bill.lines[0].taxableAmount.let { 100.0 - it }, 0.0)
        assertEquals(33.33, bill.lines[1].taxableAmount.let { 200.0 - it }, 0.0)
        // The two line discounts must sum to exactly the bill discount.
        val totalLineDiscount = (100.0 - bill.lines[0].taxableAmount) + (200.0 - bill.lines[1].taxableAmount)
        assertEquals(50.0, totalLineDiscount, 0.0001)
        assertEquals(280.0, bill.grandTotal, 0.0)
    }

    @Test
    fun billDiscount_clampsToGrossSubtotal_neverGoesNegative() {
        // A discount larger than the bill itself (e.g. a fat-fingered entry,
        // or a coupon misconfigured server-side) must clamp to the gross
        // subtotal, not drive the taxable value or grand total negative.
        val item = CartItem(product(price = 100.0, cgst = 6.0, sgst = 6.0), quantity = 1.0)
        val bill = GstBillingCalculator.calculate(
            items = listOf(item),
            gstScheme = GstBillingCalculator.SCHEME_NORMAL,
            sellerStateCode = "27",
            buyerStateCode = "27",
            billDiscount = 1000.0
        )
        assertEquals(100.0, bill.subtotal, 0.0)
        assertEquals(100.0, bill.discount, 0.0)
        assertEquals(0.0, bill.taxableValue, 0.0)
        assertEquals(0.0, bill.totalTax, 0.0)
        assertEquals(0.0, bill.grandTotal, 0.0)
    }

    @Test
    fun round2Pub_roundsToTwoDecimalPlaces() {
        assertEquals(10.34, GstBillingCalculator.round2Pub(10.336), 0.0)
        assertEquals(10.33, GstBillingCalculator.round2Pub(10.334), 0.0)
        assertEquals(0.0, GstBillingCalculator.round2Pub(0.0), 0.0)
    }
}
