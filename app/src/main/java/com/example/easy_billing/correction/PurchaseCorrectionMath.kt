package com.example.easy_billing.correction

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Purchase correction notes (QDN / PDN), Phase 3 — the pure calculator.
 *
 * Turns "what the user wants to correct" into "exactly what changes":
 * the note(s) to save, the new stock average, the profit variance, the
 * batch edits and the inventory log. It has NO Android, database or
 * network code, so it is unit-tested with thousands of random cases and
 * is the single source of truth for the A5 formulas in the plan.
 *
 * Conventions
 *  - Every rate and average is GST-inclusive ("gross") unless it says "net".
 *  - Signed values: negative = the bill goes DOWN (Debit note, D),
 *    positive = the bill goes UP (Credit note, C).
 *  - variance: positive = LOSS, negative = GAIN (same as
 *    PurchaseReturn.inventoryValuationVariance).
 *  - Money is rounded to 2 decimals per note; the shelf part is rounded
 *    first and the "gone" part is whatever is left, so
 *        note value = change in stock value + variance
 *    holds EXACTLY (see [Correction.invariantGap]).
 */
object PurchaseCorrectionMath {

    private const val EPS = 1e-9

    // ───────────────────────── inputs ─────────────────────────

    /**
     * The bill line being corrected.
     *  held  H: pens of this bill still owned (bought + additions − returns ± qty corrections)
     *  shelf S: pens of this bill still unsold (sum of its batch counters), 0 ≤ S ≤ H
     *  stock / averageCost: the product's TOTAL stock and gross moving average now
     *  tracksStock: false for asset / raw-material lines (value and GST only)
     */
    data class LineState(
        val held: Double,
        val shelf: Double,
        val stock: Double,
        val averageCost: Double,
        val tracksStock: Boolean = true,
        val purchaseCancelled: Boolean = false
    )

    /** Current (already corrected) rates and the wanted new ones, per pen. */
    data class PriceChange(
        val oldNetRate: Double,
        val oldGrossRate: Double,
        val newNetRate: Double,
        val newGrossRate: Double
    )

    /** pens > 0; netRate / grossRate are the line's CURRENT corrected rates. */
    data class QtyChange(
        val pens: Double,
        val increase: Boolean,
        val netRate: Double,
        val grossRate: Double
    )

    // ───────────────────────── outputs ─────────────────────────

    enum class Kind { PRICE_CORRECTION, QTY_CORRECTION }

    enum class BlockReason {
        INVALID_INPUT, CANCELLED_PURCHASE, NOTHING_HELD, NO_CHANGE,
        QDN_EXCEEDS_SHELF, NEGATIVE_AVERAGE
    }

    /** One GSTR-2 note. [value], [taxable], [tax] are positive; [direction] says which way. */
    data class NotePart(
        val direction: String,          // "D" or "C"
        val value: Double,              // gross, positive
        val taxable: Double,            // positive (0 for a tax-only note)
        val tax: Double,                // positive (0 for a taxable-only note)
        val quantity: Double,           // pens the note covers
        val rateDeltaNet: Double,       // per pen, signed new − old (0 for QDN / tax-only)
        val shelfValueDelta: Double,    // signed; part that went into the average
        val variance: Double            // signed; + loss / − gain
    ) {
        val signedValue: Double get() = if (direction == "D") -value else value
    }

    data class LogSpec(
        val type: String,               // QTY_CORR_ADD | QTY_CORR_REDUCE | COST_ADJUST
        val quantity: Double,
        val price: Double,
        val resultingAverage: Double
    )

    /** Edits for this bill's purchase batch. Phase 4 applies them. */
    data class BatchEffect(
        val quantityRemainingDelta: Double = 0.0,
        val quantityPurchasedDelta: Double = 0.0,
        val invoiceValueDelta: Double = 0.0,
        val netRateDeltaPerPen: Double = 0.0,
        val grossRateDeltaPerPen: Double = 0.0,
        /** QDN increase adds a NEW batch at the bill's current rate. */
        val newBatchQuantity: Double = 0.0,
        val newBatchNetRate: Double = 0.0,
        val newBatchGrossRate: Double = 0.0
    )

    data class Correction(
        val kind: Kind,
        val parts: List<NotePart>,
        val newStock: Double,
        val newAverage: Double,
        val log: LogSpec?,
        val batch: BatchEffect
    ) {
        /** Change in the supplier's bill (negative = you owe less). */
        val signedNoteValue: Double get() = round2(parts.sumOf { it.signedValue })
        val totalVariance: Double get() = round2(parts.sumOf { it.variance })
        val totalShelfValueDelta: Double get() = round2(parts.sumOf { it.shelfValueDelta })

        /**
         * note value − (change in stock value + variance). Must be 0 (to
         * floating-point noise) for every correction — the plan's invariant.
         */
        fun invariantGap(before: LineState): Double {
            val stockValueChange = newStock * newAverage - before.stock * before.averageCost
            return signedNoteValue - (stockValueChange + totalVariance)
        }
    }

    sealed class Outcome {
        data class Ok(val correction: Correction) : Outcome()
        data class Blocked(val reason: BlockReason, val message: String) : Outcome()
    }

    // ───────────────────────── helpers ─────────────────────────

    /** Round to 2 decimals, half away from zero (money). */
    fun round2(x: Double): Double =
        BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_UP).toDouble()

    /** Gross rate from a net rate and the TOTAL tax percent (CGST+SGST or IGST, plus cess). */
    fun grossRate(netRate: Double, totalTaxPercent: Double): Double =
        netRate * (1.0 + totalTaxPercent / 100.0)

    /**
     * Spread a bill-level [total] over lines in proportion to [weights],
     * each rounded to 2 decimals; the LAST line absorbs the leftover paisa
     * so the parts always add up to the rounded total.
     */
    fun splitAcrossLines(total: Double, weights: List<Double>): List<Double> {
        if (weights.isEmpty()) return emptyList()
        val target = round2(total)
        val sum = weights.sum()
        val out = ArrayList<Double>(weights.size)
        var given = 0.0
        for ((i, w) in weights.withIndex()) {
            if (i == weights.lastIndex) {
                out.add(round2(target - given))
            } else {
                val share = if (abs(sum) < EPS) 0.0 else round2(target * w / sum)
                out.add(share)
                given = round2(given + share)
            }
        }
        return out
    }

    private fun finite(vararg v: Double) = v.all { it.isFinite() }

    private fun blocked(r: BlockReason, m: String) = Outcome.Blocked(r, m)

    private fun validateLine(s: LineState): Outcome.Blocked? {
        if (!finite(s.held, s.shelf, s.stock, s.averageCost) ||
            s.held < 0 || s.shelf < -EPS || s.stock < -EPS || s.averageCost < -EPS ||
            s.shelf > s.held + EPS ||
            (s.tracksStock && s.shelf > s.stock + EPS)
        ) return blocked(BlockReason.INVALID_INPUT, "The bill figures are not consistent. Please refresh and try again.")
        if (s.purchaseCancelled)
            return blocked(BlockReason.CANCELLED_PURCHASE, "This purchase is cancelled, so it can no longer be corrected.")
        if (s.held <= EPS)
            return blocked(BlockReason.NOTHING_HELD, "No pens of this bill are held any more (all were returned), so there is nothing to correct.")
        return null
    }

    // ───────────────────────── PDN: price / tax correction ─────────────────────────

    fun priceCorrection(line: LineState, change: PriceChange): Outcome {
        validateLine(line)?.let { return it }
        if (!finite(change.oldNetRate, change.oldGrossRate, change.newNetRate, change.newGrossRate) ||
            change.oldNetRate < 0 || change.oldGrossRate < 0 || change.newNetRate < 0 || change.newGrossRate < 0
        ) return blocked(BlockReason.INVALID_INPUT, "The rates entered are not valid.")

        val dN = change.newNetRate - change.oldNetRate
        val dG = change.newGrossRate - change.oldGrossRate
        val H = line.held
        val S = if (line.tracksStock) line.shelf else 0.0

        val valueTotal = round2(H * dG)
        val taxableTotal = round2(H * dN)
        val taxTotal = round2(valueTotal - taxableTotal)
        if (valueTotal == 0.0 && taxableTotal == 0.0 && taxTotal == 0.0)
            return blocked(BlockReason.NO_CHANGE, "The corrected price and tax give the same bill value, so there is nothing to correct.")

        val shelfTotal = round2(S * dG)
        val mixed = taxableTotal != 0.0 && taxTotal != 0.0 && (taxableTotal > 0) != (taxTotal > 0)

        val parts = ArrayList<NotePart>(2)
        fun part(signed: Double, taxable: Double, tax: Double, shelf: Double, rateDeltaNet: Double) {
            val gone = round2(signed - shelf)
            parts.add(
                NotePart(
                    direction = if (signed < 0) "D" else "C",
                    value = abs(signed), taxable = abs(taxable), tax = abs(tax),
                    quantity = H, rateDeltaNet = rateDeltaNet,
                    shelfValueDelta = shelf,
                    variance = if (line.tracksStock) gone else 0.0
                )
            )
        }
        if (mixed) {
            // D11: opposite directions -> two single-signed notes.
            val shelfTaxable = round2(S * dN)
            part(taxableTotal, taxableTotal, 0.0, shelfTaxable, dN)
            part(taxTotal, 0.0, taxTotal, round2(shelfTotal - shelfTaxable), 0.0)
        } else {
            part(valueTotal, taxableTotal, taxTotal, shelfTotal, dN)
        }
        // A non-stock line has no shelf and no variance at all.
        val finalParts = if (line.tracksStock) parts else parts.map { it.copy(shelfValueDelta = 0.0, variance = 0.0) }

        var newAvg = line.averageCost
        var log: LogSpec? = null
        if (line.tracksStock && S > EPS && line.stock > EPS && shelfTotal != 0.0) {
            newAvg = (line.stock * line.averageCost + shelfTotal) / line.stock
            if (newAvg < -EPS)
                return blocked(BlockReason.NEGATIVE_AVERAGE, "This correction would make the average cost negative, so it cannot be saved.")
            if (newAvg < 0) newAvg = 0.0
            log = LogSpec("COST_ADJUST", 0.0, 0.0, newAvg)
        }
        val shelfOk = if (line.tracksStock) shelfTotal else 0.0
        // keep the exact-invariant: shelf parts of the saved notes equal what moved the average
        if (line.tracksStock && log == null && shelfOk != 0.0) {
            // shelf value with no stock to carry it -> only possible if S>0 but stock==0 (rejected by validateLine)
            return blocked(BlockReason.INVALID_INPUT, "The bill figures are not consistent. Please refresh and try again.")
        }

        return Outcome.Ok(
            Correction(
                kind = Kind.PRICE_CORRECTION,
                parts = finalParts,
                newStock = line.stock,
                newAverage = newAvg,
                log = log,
                batch = if (line.tracksStock)
                    BatchEffect(netRateDeltaPerPen = dN, grossRateDeltaPerPen = dG)
                else BatchEffect()
            )
        )
    }

    // ───────────────────────── QDN: quantity correction ─────────────────────────

    fun quantityCorrection(line: LineState, change: QtyChange): Outcome {
        validateLine(line)?.let { return it }
        if (!finite(change.pens, change.netRate, change.grossRate) ||
            change.pens <= 0 || change.netRate < 0 || change.grossRate < 0
        ) return blocked(BlockReason.INVALID_INPUT, "The quantity or rate entered is not valid.")

        val dq = change.pens
        val value = round2(dq * change.grossRate)
        val taxable = round2(dq * change.netRate)
        val tax = round2(value - taxable)

        if (!change.increase && dq > line.shelf + EPS && line.tracksStock)
            return blocked(
                BlockReason.QDN_EXCEEDS_SHELF,
                "Only ${trim(line.shelf)} pens of this bill are still on the shelf. You cannot reduce the bill by ${trim(dq)} because the rest were already sold or scrapped."
            )
        if (!change.increase && dq > line.held + EPS)
            return blocked(BlockReason.QDN_EXCEEDS_SHELF, "You cannot reduce by more pens than this bill still holds (${trim(line.held)}).")

        val direction = if (change.increase) "C" else "D"
        var newStock = line.stock
        var newAvg = line.averageCost
        var variance = 0.0
        var log: LogSpec? = null
        var batch = BatchEffect()

        if (line.tracksStock) {
            val before = line.stock * line.averageCost
            if (change.increase) {
                newStock = line.stock + dq
                newAvg = (before + value) / newStock
                log = LogSpec("QTY_CORR_ADD", dq, change.grossRate, newAvg)
                batch = BatchEffect(newBatchQuantity = dq, newBatchNetRate = change.netRate, newBatchGrossRate = change.grossRate)
            } else {
                newStock = line.stock - dq
                val after = before - value
                if (newStock <= EPS) {
                    // Everything is gone: whatever value is left over cannot stay in an empty shelf.
                    newStock = 0.0
                    newAvg = 0.0
                    variance = after          // leftover > 0 is a loss, < 0 a gain
                } else {
                    newAvg = after / newStock
                    if (newAvg < -EPS)
                        return blocked(BlockReason.NEGATIVE_AVERAGE, "This correction would make the average cost negative, so it cannot be saved.")
                    if (newAvg < 0) newAvg = 0.0
                }
                log = LogSpec("QTY_CORR_REDUCE", dq, change.grossRate, newAvg)
                batch = BatchEffect(
                    quantityRemainingDelta = -dq, quantityPurchasedDelta = -dq, invoiceValueDelta = -value
                )
            }
        }

        val part = NotePart(
            direction = direction, value = value, taxable = taxable, tax = tax,
            quantity = dq, rateDeltaNet = 0.0, shelfValueDelta = 0.0, variance = round2(variance)
        )
        return Outcome.Ok(Correction(Kind.QTY_CORRECTION, listOf(part), newStock, newAvg, log, batch))
    }

    private fun trim(x: Double): String =
        if (x == Math.floor(x)) x.toLong().toString() else x.toString()
}
