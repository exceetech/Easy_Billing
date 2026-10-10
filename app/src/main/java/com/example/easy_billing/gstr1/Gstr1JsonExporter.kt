package com.example.easy_billing.gstr1

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Simple numbers for the owner-facing GST summary: sales and GST after credit/debit notes.
 */
object GstSimpleMath {

    private fun sign(noteType: String) = if (noteType.trim().equals("D", ignoreCase = true)) 1.0 else -1.0

    /** Sales before GST, after credit notes (minus) and debit notes (plus). */
    fun netTaxable(r: Gstr1Report): Double =
        r.b2b.sumOf { it.taxableValue } + r.b2cl.sumOf { it.taxableValue } + r.b2cs.sumOf { it.taxableValue } +
            r.cdnr.sumOf { sign(it.noteType) * it.taxableValue } +
            r.cdnur.sumOf { sign(it.noteType) * it.taxableValue }

    /** GST collected on those sales, after credit/debit notes. */
    fun netTax(r: Gstr1Report): Double =
        r.b2b.sumOf { it.taxableValue * it.rate / 100.0 } +
            r.b2cl.sumOf { it.taxableValue * it.rate / 100.0 } +
            r.b2cs.sumOf { it.taxableValue * it.rate / 100.0 } +
            r.cdnr.sumOf { sign(it.noteType) * it.taxableValue * it.rate / 100.0 } +
            r.cdnur.sumOf { sign(it.noteType) * it.taxableValue * it.rate / 100.0 }

    /** True when there is nothing at all to report for the period. */
    fun isNil(r: Gstr1Report): Boolean =
        r.b2b.isEmpty() && r.b2cl.isEmpty() && r.b2cs.isEmpty() && r.cdnr.isEmpty() && r.cdnur.isEmpty()
}

/**
 * Writes the GSTR-1 report as a JSON file in the GST portal's offline-upload layout
 * (gstin, fp, b2b / b2cl / b2cs / cdnr / cdnur / hsn / doc_issue).
 *
 * NOTE: built from the published GSTN JSON structure; it has not been uploaded to the live
 * portal yet. Test it once (Returns Dashboard > Prepare Offline) before relying on it.
 */
object Gstr1JsonExporter {

    data class ExportResult(val file: File, val uri: Uri)

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    private fun r2(v: Double) = Math.round(v * 100.0) / 100.0

    /** Return period as the portal writes it: MMYYYY (quarter-end month for quarterly). */
    fun fp(report: Gstr1Report): String {
        val fyStart = report.financialYear.substringBefore("-").toIntOrNull() ?: return ""
        val monthName = when (report.period) {
            "Apr-Jun" -> "June"; "Jul-Sep" -> "September"; "Oct-Dec" -> "December"; "Jan-Mar" -> "March"
            else -> report.period
        }
        val m = MONTHS.indexOf(monthName) + 1
        if (m == 0) return ""
        val year = if (m >= 4) fyStart else fyStart + 1
        return "%02d%d".format(m, year)
    }

    private fun pos(placeOfSupply: String) = placeOfSupply.trim().take(2)

    private fun date(d: String): String {
        val src = SimpleDateFormat("dd-MMM-yy", Locale.ENGLISH)
        val dst = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
        return try { dst.format(src.parse(d.trim())!!) } catch (e: Exception) { d }
    }

    private fun invTyp(t: String) = when {
        t.contains("SEZ", true) -> "SEWP"
        t.contains("Deemed", true) -> "DE"
        else -> "R"
    }

    /** Tax split: IGST when the place of supply differs from the shop's state, else CGST + SGST. */
    private fun det(gstin: String, posCode: String, rate: Double, txval: Double, cess: Double): Map<String, Any> {
        val tax = r2(txval * rate / 100.0)
        val m = linkedMapOf<String, Any>("rt" to rate, "txval" to r2(txval))
        if (gstin.take(2) == posCode) {
            m["camt"] = r2(tax / 2.0); m["samt"] = r2(tax / 2.0)
        } else {
            m["iamt"] = tax
        }
        m["csamt"] = r2(cess)
        return m
    }

    private fun docCode(nature: String) = when {
        nature.contains("outward", true) -> 1
        nature.contains("inward", true) -> 2
        nature.contains("revised", true) -> 3
        nature.contains("debit", true) -> 4
        nature.contains("credit", true) -> 5
        nature.contains("receipt", true) -> 6
        nature.contains("payment", true) -> 7
        nature.contains("refund", true) -> 8
        nature.contains("job", true) -> 9
        nature.contains("approval", true) -> 10
        nature.contains("liquid", true) -> 11
        else -> 12
    }

    fun build(report: Gstr1Report): Map<String, Any> {
        val g = report.gstin
        val root = linkedMapOf<String, Any>("gstin" to g, "fp" to fp(report))

        if (report.b2b.isNotEmpty()) {
            root["b2b"] = report.b2b.groupBy { it.gstin }.map { (ctin, rows) ->
                linkedMapOf<String, Any>(
                    "ctin" to ctin,
                    "inv" to rows.groupBy { it.invoiceNumber }.map { (num, rs) ->
                        val r0 = rs.first()
                        linkedMapOf<String, Any>(
                            "inum" to num, "idt" to date(r0.invoiceDate), "val" to r2(r0.invoiceValue),
                            "pos" to pos(r0.placeOfSupply),
                            "rchrg" to r0.reverseCharge.ifBlank { "N" }, "inv_typ" to invTyp(r0.invoiceType),
                            "itms" to rs.mapIndexed { i, r ->
                                mapOf("num" to i + 1, "itm_det" to det(g, pos(r0.placeOfSupply), r.rate, r.taxableValue, r.cessAmount))
                            }
                        )
                    }
                )
            }
        }

        if (report.b2cl.isNotEmpty()) {
            root["b2cl"] = report.b2cl.groupBy { pos(it.placeOfSupply) }.map { (p, rows) ->
                linkedMapOf<String, Any>(
                    "pos" to p,
                    "inv" to rows.groupBy { it.invoiceNumber }.map { (num, rs) ->
                        val r0 = rs.first()
                        linkedMapOf<String, Any>(
                            "inum" to num, "idt" to date(r0.invoiceDate), "val" to r2(r0.invoiceValue),
                            "itms" to rs.mapIndexed { i, r ->
                                mapOf("num" to i + 1, "itm_det" to det(g, p, r.rate, r.taxableValue, r.cessAmount))
                            }
                        )
                    }
                )
            }
        }

        if (report.b2cs.isNotEmpty()) {
            root["b2cs"] = report.b2cs.map { r ->
                val p = pos(r.placeOfSupply)
                val intra = g.take(2) == p
                val tax = r2(r.taxableValue * r.rate / 100.0)
                val m = linkedMapOf<String, Any>(
                    "sply_ty" to (if (intra) "INTRA" else "INTER"), "rt" to r.rate,
                    "typ" to r.type.ifBlank { "OE" }, "pos" to p, "txval" to r2(r.taxableValue)
                )
                if (r.ecomGstin.isNotBlank()) m["etin"] = r.ecomGstin
                if (intra) { m["camt"] = r2(tax / 2.0); m["samt"] = r2(tax / 2.0) } else m["iamt"] = tax
                m["csamt"] = r2(r.cessAmount)
                m
            }
        }

        if (report.cdnr.isNotEmpty()) {
            root["cdnr"] = report.cdnr.groupBy { it.gstin }.map { (ctin, rows) ->
                linkedMapOf<String, Any>(
                    "ctin" to ctin,
                    "nt" to rows.groupBy { it.noteNumber }.map { (num, rs) ->
                        val r0 = rs.first()
                        linkedMapOf<String, Any>(
                            "ntty" to r0.noteType.trim().take(1).uppercase(), "nt_num" to num,
                            "nt_dt" to date(r0.noteDate), "val" to r2(r0.noteValue),
                            "pos" to pos(r0.placeOfSupply), "rchrg" to r0.reverseCharge.ifBlank { "N" },
                            "inv_typ" to invTyp(r0.noteSupplyType),
                            "itms" to rs.mapIndexed { i, r ->
                                mapOf("num" to i + 1, "itm_det" to det(g, pos(r0.placeOfSupply), r.rate, r.taxableValue, r.cessAmount))
                            }
                        )
                    }
                )
            }
        }

        if (report.cdnur.isNotEmpty()) {
            root["cdnur"] = report.cdnur.groupBy { it.noteNumber }.map { (num, rs) ->
                val r0 = rs.first()
                linkedMapOf<String, Any>(
                    "typ" to (if (r0.urType.contains("EXP", true)) "EXPWP" else "B2CL"),
                    "ntty" to r0.noteType.trim().take(1).uppercase(), "nt_num" to num,
                    "nt_dt" to date(r0.noteDate), "val" to r2(r0.noteValue), "pos" to pos(r0.placeOfSupply),
                    "itms" to rs.mapIndexed { i, r ->
                        mapOf("num" to i + 1, "itm_det" to det(g, pos(r0.placeOfSupply), r.rate, r.taxableValue, r.cessAmount))
                    }
                )
            }
        }

        fun hsnList(rows: List<HsnRow>) = rows.mapIndexed { i, r ->
            linkedMapOf<String, Any>(
                "num" to i + 1, "hsn_sc" to r.hsn, "desc" to r.description,
                "uqc" to r.uqc.substringBefore("-").trim().ifBlank { "OTH" },
                "qty" to r2(r.totalQuantity), "rt" to r.rate, "val" to r2(r.totalValue),
                "txval" to r2(r.taxableValue), "iamt" to r2(r.igstAmount), "camt" to r2(r.cgstAmount),
                "samt" to r2(r.sgstAmount), "csamt" to r2(r.cessAmount)
            )
        }
        if (report.hsnB2B.isNotEmpty() || report.hsnB2C.isNotEmpty()) {
            root["hsn"] = linkedMapOf<String, Any>(
                "hsn_b2b" to hsnList(report.hsnB2B),
                "hsn_b2c" to hsnList(report.hsnB2C)
            )
        }

        if (report.docs.isNotEmpty()) {
            root["doc_issue"] = mapOf(
                "doc_det" to report.docs.groupBy { docCode(it.natureOfDoc) }.map { (code, rs) ->
                    mapOf(
                        "doc_num" to code,
                        "docs" to rs.mapIndexed { i, d ->
                            mapOf(
                                "num" to i + 1, "from" to d.srFrom, "to" to d.srTo,
                                "totnum" to d.totalNumber, "cancel" to d.cancelled,
                                "net_issue" to (d.totalNumber - d.cancelled).coerceAtLeast(0)
                            )
                        }
                    )
                }
            )
        }
        return root
    }

    fun export(context: Context, report: Gstr1Report): ExportResult {
        val json = GsonBuilder().create().toJson(build(report))
        val dir = File(
            context.getExternalFilesDir(null),
            "GSTR1_${report.gstin}_${report.financialYear}_${report.period}"
        ).also { it.mkdirs() }
        val file = File(dir, "returns_${fp(report)}_R1_${report.gstin}_offline.json")
        file.writeText(json)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        return ExportResult(file, uri)
    }
}
