package com.example.easy_billing.db

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stage-1 P0-1 / P0-2: gross-vs-net batch cost used in return variance. */
class PurchaseBatchGrossCostTest {

    private fun batch(invoiceValue: Double, qty: Double, net: Double, gst: Double = 18.0) =
        PurchaseBatch(
            productId = 1, quantityPurchased = qty, quantityRemaining = qty,
            unitCostExcludingTax = net, cgstPercent = gst / 2, sgstPercent = gst / 2,
            invoiceValue = invoiceValue
        )

    @Test fun t0_1a_sameCostReturnHasZeroVariance() {
        val b = batch(invoiceValue = 1180.0, qty = 100.0, net = 10.0)
        val avg = 11.80
        assertEquals(0.0, 10 * avg - 10 * b.grossUnitCost(), 0.001)
    }

    @Test fun t0_1b_realLossIsOnlyThePriceDifference() {
        val b = batch(invoiceValue = 1180.0, qty = 100.0, net = 10.0)   // gross 11.80
        val avg = 12.19
        assertEquals(3.90, 10 * avg - 10 * b.grossUnitCost(), 0.001)
    }

    @Test fun t0_1c_zeroGstBatchUnchanged() {
        val b = batch(invoiceValue = 1000.0, qty = 100.0, net = 10.0, gst = 0.0)
        assertEquals(10.0, b.grossUnitCost(), 0.001)
    }

    @Test fun t0_2_fallbackUsesNetTimesGstWhenInvoiceValueMissing() {
        val b = batch(invoiceValue = 0.0, qty = 100.0, net = 10.0)
        assertEquals(11.80, b.grossUnitCost(), 0.001)
    }
}
