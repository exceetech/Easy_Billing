package com.example.easy_billing.correction

import com.example.easy_billing.correction.PurchaseCorrectionMath.round2
import kotlin.math.abs

/**
 * Purchase correction notes, Phase 4 — the pure planner.
 *
 * Sits between the database and [PurchaseCorrectionMath]: it works out the
 * line's CURRENT (already corrected) rates from the bill plus the price
 * notes filed so far, turns a user request into the exact note rows, tax
 * split, ITC and batch edits to save, and picks which batches a quantity
 * reduction takes pens from. No Android / Room code, so it is unit-tested
 * on the JVM. The repository only loads the snapshots and writes the plan.
 */
object PurchaseCorrectionPlanner {

    private const val EPS = 1e-9

    // ───────────────────────── snapshots (plain data) ─────────────────────────

    data class ItemSnap(
        val quantity: Double,
        val taxableAmount: Double,
        val invoiceValue: Double,
        val cgstPct: Double,
        val sgstPct: Double,
        val igstPct: Double,
        val cessPct: Double,
        val cessAmount: Double
    )

    /** A price note already filed on the line (values unsigned, direction says the way). */
    data class PriceNoteSnap(
        val direction: String,      // "D" / "C"
        val taxable: Double,
        val value: Double,          // note/refund voucher value (gross)
        val cessAmount: Double,
        val quantity: Double,       // pens covered
        val cgstPct: Double,
        val sgstPct: Double,
        val igstPct: Double
    )

    /** Current corrected per-pen rates of a bill line. */
    data class LineRates(
        val net: Double,
        val gross: Double,
        val cessPerPen: Double,
        val cgstPct: Double,
        val sgstPct: Double,
        val igstPct: Double
    ) {
        val taxPct: Double get() = cgstPct + sgstPct + igstPct
    }

    /** Notes must be passed oldest first; the last one supplies the current percentages. */
    fun currentRates(item: ItemSnap, notes: List<PriceNoteSnap>): LineRates {
        val q = item.quantity
        var net = if (q > 0) item.taxableAmount / q else 0.0
        var gross = if (q > 0) item.invoiceValue / q else 0.0
        var cess = if (q > 0 && item.cessAmount > 0.0) item.cessAmount / q else net * item.cessPct / 100.0
        for (n in notes) {
            if (n.quantity <= 0.0) continue
            val s = if (n.direction.equals("C", true)) 1.0 else -1.0
            net += s * n.taxable / n.quantity
            gross += s * n.value / n.quantity
            cess += s * n.cessAmount / n.quantity
        }
        val last = notes.lastOrNull()
        return LineRates(
            net = net, gross = gross, cessPerPen = cess,
            cgstPct = last?.cgstPct ?: item.cgstPct,
            sgstPct = last?.sgstPct ?: item.sgstPct,
            igstPct = last?.igstPct ?: item.igstPct
        )
    }

    // ───────────────────────── plan output ─────────────────────────

    /** One note row to insert. All amounts are positive; [direction] is the way. */
    data class NoteRow(
        val direction: String,
        val taxable: Double,
        val cgst: Double,
        val sgst: Double,
        val igst: Double,
        val cess: Double,
        val value: Double,
        val quantity: Double,
        val cgstPct: Double,
        val sgstPct: Double,
        val igstPct: Double,
        val rateDeltaNet: Double,
        val shelfValueDelta: Double,
        val variance: Double,
        val availedCgst: Double,
        val availedSgst: Double,
        val availedIgst: Double,
        val availedCess: Double
    ) {
        val signedValue: Double get() = if (direction == "D") -value else value
    }

    /** How a PDN edits the bill's batches (per pen, applied to EVERY batch of the bill/product). */
    data class BatchPatch(
        val netDelta: Double,
        val grossDelta: Double,
        val cgstPct: Double,
        val sgstPct: Double,
        val igstPct: Double
    )

    data class BatchTake(val batchId: Int, val quantity: Double)

    data class Plan(
        val correction: PurchaseCorrectionMath.Correction,
        val rows: List<NoteRow>,
        val newRates: LineRates,
        /** PDN: edit every batch of this bill+product. */
        val batchPatch: BatchPatch?,
        /** QDN decrease: pens taken oldest-first. */
        val takes: List<BatchTake>,
        /** QDN increase: the new batch to add (qty, net, gross) or null. */
        val newBatch: PurchaseCorrectionMath.BatchEffect?
    ) {
        val signedNoteValue: Double get() = round2(rows.sumOf { it.signedValue })
    }

    sealed class Result {
        data class Ok(val plan: Plan) : Result()
        data class Blocked(val message: String, val reason: PurchaseCorrectionMath.BlockReason? = null) : Result()
    }

    // ───────────────────────── requests ─────────────────────────

    /** Absolute target for a PDN. [newCessPct] null keeps the cess percentage the line has now. */
    data class PriceRequest(
        val newNetRate: Double,
        val newCgstPct: Double,
        val newSgstPct: Double,
        val newIgstPct: Double,
        val newCessPct: Double? = null
    )

    /** ITC carried by the original line, to scale the availed ITC of notes. */
    data class ItcInfo(
        val eligible: Boolean,
        val cgstRatio: Double = 1.0,
        val sgstRatio: Double = 1.0,
        val igstRatio: Double = 1.0,
        val cessRatio: Double = 1.0
    )

    // ───────────────────────── helpers ─────────────────────────

    /**
     * Split a signed tax total over [raw] components (cgst, sgst, igst, cess):
     * each rounded to 2 decimals, the leftover paisa added to the largest.
     * Returns null when a non-zero component points the other way from the total.
     */
    fun allocateTax(total: Double, raw: DoubleArray): DoubleArray? {
        val t = round2(total)
        val out = DoubleArray(raw.size) { round2(raw[it]) }
        for (v in out) if (v != 0.0 && (v > 0) != (t > 0)) return null
        if (t == 0.0) return if (out.all { it == 0.0 }) out else null
        var idx = 0
        for (i in out.indices) if (abs(out[i]) > abs(out[idx])) idx = i
        out[idx] = round2(out[idx] + (t - out.sum()))
        for (v in out) if (v != 0.0 && (v > 0) != (t > 0)) return null
        return out
    }

    private fun itcOf(info: ItcInfo, cgst: Double, sgst: Double, igst: Double, cess: Double): DoubleArray =
        if (!info.eligible) DoubleArray(4)
        else doubleArrayOf(
            round2(cgst * info.cgstRatio), round2(sgst * info.sgstRatio),
            round2(igst * info.igstRatio), round2(cess * info.cessRatio)
        )

    // ───────────────────────── PDN ─────────────────────────

    fun planPrice(
        line: PurchaseCorrectionMath.LineState,
        rates: LineRates,
        req: PriceRequest,
        itc: ItcInfo
    ): Result {
        if (listOf(req.newNetRate, req.newCgstPct, req.newSgstPct, req.newIgstPct).any { !it.isFinite() || it < 0 } ||
            (req.newCessPct != null && (!req.newCessPct.isFinite() || req.newCessPct < 0))
        ) return Result.Blocked("The rate or tax percentage entered is not valid.", PurchaseCorrectionMath.BlockReason.INVALID_INPUT)

        if (req.newIgstPct > EPS && (req.newCgstPct > EPS || req.newSgstPct > EPS))
            return Result.Blocked("A line cannot carry both IGST and CGST/SGST. Use IGST for an other-state supplier, or CGST + SGST for the same state.", PurchaseCorrectionMath.BlockReason.INVALID_INPUT)
        if (abs(req.newCgstPct - req.newSgstPct) > 1e-6)
            return Result.Blocked("CGST and SGST must be the same percentage.", PurchaseCorrectionMath.BlockReason.INVALID_INPUT)

        val cessPctNow = if (rates.net > EPS) rates.cessPerPen / rates.net * 100.0 else 0.0
        val newCessPerPen = if (req.newCessPct != null) req.newNetRate * req.newCessPct / 100.0
        else if (rates.net > EPS) req.newNetRate * cessPctNow / 100.0 else rates.cessPerPen
        val newTaxPct = req.newCgstPct + req.newSgstPct + req.newIgstPct

        val gOldFormula = rates.net * (1 + rates.taxPct / 100.0) + rates.cessPerPen
        val gNewFormula = req.newNetRate * (1 + newTaxPct / 100.0) + newCessPerPen
        val newGross = rates.gross + (gNewFormula - gOldFormula)

        val out = PurchaseCorrectionMath.priceCorrection(
            line, PurchaseCorrectionMath.PriceChange(rates.net, rates.gross, req.newNetRate, newGross)
        )
        val corr = when (out) {
            is PurchaseCorrectionMath.Outcome.Blocked -> return Result.Blocked(out.message, out.reason)
            is PurchaseCorrectionMath.Outcome.Ok -> out.correction
        }

        val h = line.held
        val taxSigned = round2(corr.parts.sumOf { if (it.direction == "D") -it.tax else it.tax })
        val raw = doubleArrayOf(
            h * (req.newNetRate * req.newCgstPct / 100.0 - rates.net * rates.cgstPct / 100.0),
            h * (req.newNetRate * req.newSgstPct / 100.0 - rates.net * rates.sgstPct / 100.0),
            h * (req.newNetRate * req.newIgstPct / 100.0 - rates.net * rates.igstPct / 100.0),
            h * (newCessPerPen - rates.cessPerPen)
        )
        val comps = allocateTax(taxSigned, raw)
            ?: return Result.Blocked(
                "This change moves the tax components in opposite directions. Please correct it in two steps (rate first, then tax percentage).",
                PurchaseCorrectionMath.BlockReason.INVALID_INPUT
            )

        val rows = corr.parts.map { p ->
            val carriesTax = p.tax > 0.0
            val c = if (carriesTax) comps.map { abs(it) } else listOf(0.0, 0.0, 0.0, 0.0)
            val a = itcOf(itc, c[0], c[1], c[2], c[3])
            NoteRow(
                direction = p.direction, taxable = p.taxable,
                cgst = c[0], sgst = c[1], igst = c[2], cess = c[3],
                value = p.value, quantity = p.quantity,
                cgstPct = req.newCgstPct, sgstPct = req.newSgstPct, igstPct = req.newIgstPct,
                rateDeltaNet = p.rateDeltaNet, shelfValueDelta = p.shelfValueDelta, variance = p.variance,
                availedCgst = a[0], availedSgst = a[1], availedIgst = a[2], availedCess = a[3]
            )
        }
        val newRates = LineRates(req.newNetRate, newGross, newCessPerPen, req.newCgstPct, req.newSgstPct, req.newIgstPct)
        val patch = if (line.tracksStock)
            BatchPatch(corr.batch.netRateDeltaPerPen, corr.batch.grossRateDeltaPerPen, req.newCgstPct, req.newSgstPct, req.newIgstPct)
        else null
        return Result.Ok(Plan(corr, rows, newRates, patch, emptyList(), null))
    }

    // ───────────────────────── QDN ─────────────────────────

    /** [batches] = the bill's batches for this product with stock left, oldest first. */
    fun planQuantity(
        line: PurchaseCorrectionMath.LineState,
        rates: LineRates,
        pens: Double,
        increase: Boolean,
        itc: ItcInfo,
        batches: List<BatchTake>
    ): Result {
        val out = PurchaseCorrectionMath.quantityCorrection(
            line, PurchaseCorrectionMath.QtyChange(pens, increase, rates.net, rates.gross)
        )
        val corr = when (out) {
            is PurchaseCorrectionMath.Outcome.Blocked -> return Result.Blocked(out.message, out.reason)
            is PurchaseCorrectionMath.Outcome.Ok -> out.correction
        }
        val p = corr.parts.single()
        val sign = if (p.direction == "D") -1.0 else 1.0
        val raw = doubleArrayOf(
            pens * rates.net * rates.cgstPct / 100.0,
            pens * rates.net * rates.sgstPct / 100.0,
            pens * rates.net * rates.igstPct / 100.0,
            pens * rates.cessPerPen
        ).map { it * sign }.toDoubleArray()
        val comps = allocateTax(sign * p.tax, raw)
            ?: return Result.Blocked("The tax on this line cannot be split. Please refresh and try again.", PurchaseCorrectionMath.BlockReason.INVALID_INPUT)
        val c = comps.map { abs(it) }
        val a = itcOf(itc, c[0], c[1], c[2], c[3])
        val row = NoteRow(
            direction = p.direction, taxable = p.taxable, cgst = c[0], sgst = c[1], igst = c[2], cess = c[3],
            value = p.value, quantity = p.quantity,
            cgstPct = rates.cgstPct, sgstPct = rates.sgstPct, igstPct = rates.igstPct,
            rateDeltaNet = 0.0, shelfValueDelta = 0.0, variance = p.variance,
            availedCgst = a[0], availedSgst = a[1], availedIgst = a[2], availedCess = a[3]
        )

        var takes = emptyList<BatchTake>()
        if (!increase && line.tracksStock) {
            var left = pens
            val t = ArrayList<BatchTake>()
            for (b in batches) {
                if (left <= EPS) break
                val take = minOf(b.quantity, left)
                if (take > EPS) { t.add(BatchTake(b.batchId, take)); left -= take }
            }
            if (left > 1e-6)
                return Result.Blocked("Not enough pens of this bill are on the shelf to reduce by that much. Please refresh and try again.",
                    PurchaseCorrectionMath.BlockReason.QDN_EXCEEDS_SHELF)
            takes = t
        }
        val nb = if (increase && line.tracksStock) corr.batch else null
        return Result.Ok(Plan(corr, listOf(row), rates, null, takes, nb))
    }
}
