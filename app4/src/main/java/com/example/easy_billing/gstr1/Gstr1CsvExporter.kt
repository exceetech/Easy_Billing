package com.example.easy_billing.gstr1

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter

/**
 * Gstr1CsvExporter
 *
 * Writes section-wise CSV files compatible with the GST Offline Tool v2.2.
 * Column headers match exactly what the tool expects (derived from the
 * Section_wise_CSV_files/GSTR1/ folder in the reference tool).
 *
 * All CSVs are written to the app's external cache directory and
 * returned as content:// Uris via FileProvider.
 *
 * Usage:
 *   val uris = Gstr1CsvExporter.export(context, report)
 *   // uris["B2B"] → Uri of b2b.csv
 */
object Gstr1CsvExporter {

    data class ExportResult(
        val files: Map<String, Uri>,  // section name → content Uri
        val directory: File
    )

    fun export(context: Context, report: Gstr1Report): ExportResult {
        val dir = File(context.getExternalFilesDir(null),
            "GSTR1_${report.gstin}_${report.financialYear}_${report.period}")
            .also { it.mkdirs() }

        val files = mutableMapOf<String, Uri>()

        fun write(name: String, header: String, rows: List<String>) {
            if (rows.isEmpty()) return
            val file = File(dir, "$name.csv")
            FileWriter(file).use { w ->
                w.writeln(header)
                rows.forEach { w.writeln(it) }
            }
            files[name] = FileProvider.getUriForFile(
                context, "${context.packageName}.provider", file)
        }

        // ── B2B ─────────────────────────────────────────────────────────────
        write("b2b",
            "GSTIN/UIN of Recipient,Receiver Name,Invoice Number,Invoice date,Invoice Value," +
            "Place Of Supply,Reverse Charge,Applicable % of Tax Rate,Invoice Type," +
            "E-Commerce GSTIN,Rate,Taxable Value,Cess Amount",
            report.b2b.map { r ->
                csv(r.gstin, r.receiverName, r.invoiceNumber, r.invoiceDate,
                    r.invoiceValue.fmt(), r.placeOfSupply, r.reverseCharge,
                    r.applicableRate, r.invoiceType, r.ecomGstin,
                    r.rate.fmt(), r.taxableValue.fmt(), r.cessAmount.fmt())
            }
        )

        // ── B2CL ─────────────────────────────────────────────────────────────
        write("b2cl",
            "Invoice Number,Invoice date,Invoice Value,Place Of Supply," +
            "Applicable % of Tax Rate,Rate,Taxable Value,Cess Amount,E-Commerce GSTIN",
            report.b2cl.map { r ->
                csv(r.invoiceNumber, r.invoiceDate, r.invoiceValue.fmt(),
                    r.placeOfSupply, r.applicableRate, r.rate.fmt(),
                    r.taxableValue.fmt(), r.cessAmount.fmt(), r.ecomGstin)
            }
        )

        // ── B2CS ─────────────────────────────────────────────────────────────
        write("b2cs",
            "Type,Place Of Supply,Rate,Applicable % of Tax Rate,Taxable Value,Cess Amount,E-Commerce GSTIN",
            report.b2cs.map { r ->
                csv(r.type, r.placeOfSupply, r.rate.fmt(),
                    r.applicableRate, r.taxableValue.fmt(), r.cessAmount.fmt(), r.ecomGstin)
            }
        )

        // ── CDNR ─────────────────────────────────────────────────────────────
        write("cdnr",
            "GSTIN/UIN of Recipient,Receiver Name,Note Number,Note Date,Note Type," +
            "Place Of Supply,Reverse Charge,Note Supply Type,Note Value," +
            "Applicable % of Tax Rate,Rate,Taxable Value,Cess Amount",
            report.cdnr.map { r ->
                csv(r.gstin, r.receiverName, r.noteNumber, r.noteDate, r.noteType,
                    r.placeOfSupply, r.reverseCharge, r.noteSupplyType,
                    r.noteValue.fmt(), r.applicableRate,
                    r.rate.fmt(), r.taxableValue.fmt(), r.cessAmount.fmt())
            }
        )

        // ── CDNUR ────────────────────────────────────────────────────────────
        write("cdnur",
            "UR Type,Note Number,Note Date,Note Type,Place Of Supply,Note Value," +
            "Applicable % of Tax Rate,Rate,Taxable Value,Cess Amount",
            report.cdnur.map { r ->
                csv(r.urType, r.noteNumber, r.noteDate, r.noteType,
                    r.placeOfSupply, r.noteValue.fmt(), r.applicableRate,
                    r.rate.fmt(), r.taxableValue.fmt(), r.cessAmount.fmt())
            }
        )

        // ── HSN(B2B) ─────────────────────────────────────────────────────────
        val hsnHeader = "HSN,Description,UQC,Total Quantity,Total Value,Taxable Value," +
            "Integrated Tax Amount,Central Tax Amount,State/UT Tax Amount,Cess Amount,Rate"
        write("hsn_b2b", hsnHeader, hsnRows(report.hsnB2B))
        write("hsn_b2c", hsnHeader, hsnRows(report.hsnB2C))

        // ── DOCS ─────────────────────────────────────────────────────────────
        write("docs",
            "Nature of Document,Sr. No. From,Sr. No. To,Total Number,Cancelled",
            report.docs.map { r ->
                csv(r.natureOfDoc, r.srFrom, r.srTo, r.totalNumber.toString(), r.cancelled.toString())
            }
        )

        // ── ECO ──────────────────────────────────────────────────────────────
        write("eco",
            "Nature of Supply,GSTIN of E-Commerce Operator,E-Commerce Operator Name," +
            "Net value of supplies,Integrated tax,Central tax,State/UT tax,Cess",
            report.eco.map { r ->
                csv(r.natureOfSupply, r.ecoGstin, r.ecoName, r.netValue.fmt(),
                    r.igst.fmt(), r.cgst.fmt(), r.sgst.fmt(), r.cess.fmt())
            }
        )

        // ── ECOB2B ───────────────────────────────────────────────────────────
        write("ecob2b",
            "Supplier GSTIN/UIN,Supplier Name,Recipient GSTIN/UIN,Recipient Name," +
            "Document Number,Document Date,Value of supplies made,Place Of Supply," +
            "Document type,Rate,Taxable Value,Cess Amount",
            report.ecoB2B.map { r ->
                csv(r.supplierGstin, r.supplierName, r.recipientGstin, r.recipientName,
                    r.docNumber, r.docDate, r.supplyValue.fmt(), r.placeOfSupply,
                    r.docType, r.rate.fmt(), r.taxableValue.fmt(), r.cessAmount.fmt())
            }
        )

        // ── ECOB2C ───────────────────────────────────────────────────────────
        write("ecob2c",
            "Supplier GSTIN/UIN,Supplier Name,Place Of Supply,Rate,Taxable Value,Cess Amount",
            report.ecoB2C.map { r ->
                csv(r.supplierGstin, r.supplierName, r.placeOfSupply,
                    r.rate.fmt(), r.taxableValue.fmt(), r.cessAmount.fmt())
            }
        )

        // ── ECOURP2B ─────────────────────────────────────────────────────────
        write("ecourp2b",
            "Recipient GSTIN/UIN,Recipient Name,Document Number,Document Date," +
            "Value of supplies made,Place Of Supply,Document type,Rate,Taxable Value,Cess Amount",
            report.ecoUrp2B.map { r ->
                csv(r.recipientGstin, r.recipientName, r.docNumber, r.docDate,
                    r.supplyValue.fmt(), r.placeOfSupply, r.docType,
                    r.rate.fmt(), r.taxableValue.fmt(), r.cessAmount.fmt())
            }
        )

        // ── ECOURP2C ─────────────────────────────────────────────────────────
        write("ecourp2c",
            "Place Of Supply,Rate,Taxable Value,Cess Amount",
            report.ecoUrp2C.map { r ->
                csv(r.placeOfSupply, r.rate.fmt(), r.taxableValue.fmt(), r.cessAmount.fmt())
            }
        )

        return ExportResult(files, dir)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun hsnRows(rows: List<HsnRow>) = rows.map { r ->
        csv(r.hsn, r.description, r.uqc, r.totalQuantity.fmt(),
            r.totalValue.fmt(), r.taxableValue.fmt(),
            r.igstAmount.fmt(), r.cgstAmount.fmt(), r.sgstAmount.fmt(),
            r.cessAmount.fmt(), r.rate.fmt())
    }

    /** Formats a Double to 2 decimal places. */
    private fun Double.fmt() = "%.2f".format(this)

    /** Wraps a string in quotes if it contains commas or quotes. */
    private fun escCsv(s: String): String {
        val needsQuote = s.contains(',') || s.contains('"') || s.contains('\n')
        return if (needsQuote) "\"${s.replace("\"", "\"\"")}\"" else s
    }

    private fun csv(vararg fields: String) = fields.joinToString(",") { escCsv(it) }

    private fun FileWriter.writeln(s: String) {
        write(s)
        write("\r\n")
    }
}
