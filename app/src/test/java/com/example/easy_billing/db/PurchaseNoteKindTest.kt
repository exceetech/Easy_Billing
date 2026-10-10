package com.example.easy_billing.db

import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 2: legacy NULL kinds resolve by direction; held pens never go negative. */
class PurchaseNoteKindTest {

    @Test fun legacyNullKindResolvesByDirection() {
        assertEquals(PurchaseNoteKind.RETURN, PurchaseNoteKind.effective(null, "D"))
        assertEquals(PurchaseNoteKind.RETURN, PurchaseNoteKind.effective(null, null))
        assertEquals(PurchaseNoteKind.ADDITION, PurchaseNoteKind.effective(null, "C"))
    }

    @Test fun explicitKindWins() {
        assertEquals(PurchaseNoteKind.PRICE_CORRECTION, PurchaseNoteKind.effective("PRICE_CORRECTION", "D"))
        assertEquals(PurchaseNoteKind.QTY_CORRECTION, PurchaseNoteKind.effective("QTY_CORRECTION", "C"))
    }

    @Test fun unknownKindFallsBackToLegacyRule() {
        assertEquals(PurchaseNoteKind.RETURN, PurchaseNoteKind.effective("BOGUS", "D"))
    }

    @Test fun entityExposesEffectiveKind() {
        val n = PurchaseReturn(productName = "Pen", quantityReturned = 1.0, taxableAmount = 1.0,
            invoiceValue = 1.0, createdAt = 1L, noteType = "C")
        assertEquals(PurchaseNoteKind.ADDITION, n.effectiveKind())
    }

    // T2-2 arithmetic: bill 100, D 20, C 5, QTY_CORRECTION(D) 10 -> net out 25 -> H = 75
    @Test fun heldQuantityExample() {
        assertEquals(75.0, heldQuantity(100.0, 20.0 - 5.0 + 10.0), 1e-9)
    }

    @Test fun heldQuantityNeverNegative() {
        assertEquals(0.0, heldQuantity(10.0, 12.0), 0.0)
    }
}
