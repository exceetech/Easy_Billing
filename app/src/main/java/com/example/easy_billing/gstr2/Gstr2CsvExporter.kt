package com.example.easy_billing.gstr2

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter

object Gstr2CsvExporter {

    data class ExportResult(val files: Map<String, Uri>, val directory: File)

    fun export(context: Context, report: Gstr2Report): ExportResult {
        val exportDir = File(context.cacheDir, "gstr2_csv_${report.period}_${report.financialYear}".replace(" ", "_"))
        if (!exportDir.exists()) exportDir.mkdirs()

        val files = mutableMapOf<String, Uri>()

        fun writeCsv(name: String, headers: Array<String>, rows: List<Array<String>>) {
            val file = File(exportDir, "$name.csv")
            FileWriter(file).use { writer ->
                writer.append(headers.joinToString(",") { "\"$it\"" }).append("\n")
                rows.forEach { row ->
                    writer.append(row.joinToString(",") { "\"$it\"" }).append("\n")
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            files[name] = uri
        }

        // B2B
        if (report.b2b.isNotEmpty()) {
            val headers = arrayOf(
                "GSTIN of Supplier", "Invoice Number", "Invoice date", "Invoice Value",
                "Place Of Supply", "Reverse Charge", "Invoice Type", "Rate", "Taxable Value",
                "Integrated Tax Paid", "Central Tax Paid", "State/UT Tax Paid", "Cess Paid",
                "Eligibility For ITC", "Availed ITC Integrated Tax", "Availed ITC Central Tax",
                "Availed ITC State/UT Tax", "Availed ITC Cess"
            )
            val rows = report.b2b.map {
                arrayOf(
                    it.supplierGstin, it.invoiceNumber, it.invoiceDate, it.invoiceValue.toString(),
                    it.placeOfSupply, it.reverseCharge, it.invoiceType, it.rate.toString(),
                    it.taxableValue.toString(), it.igstPaid.toString(), it.cgstPaid.toString(),
                    it.sgstPaid.toString(), it.cessPaid.toString(), it.eligibilityForItc,
                    it.availedItcIgst.toString(), it.availedItcCgst.toString(),
                    it.availedItcSgst.toString(), it.availedItcCess.toString()
                )
            }
            writeCsv("b2b", headers, rows)
        }

        // B2BUR
        if (report.b2bur.isNotEmpty()) {
            val headers = arrayOf(
                "Supplier Name", "Invoice Number", "Invoice date", "Invoice Value", "Place Of Supply",
                "Supply Type", "Rate", "Taxable Value", "Integrated Tax Paid", "Central Tax Paid",
                "State/UT Tax Paid", "Cess Paid", "Eligibility For ITC", "Availed ITC Integrated Tax",
                "Availed ITC Central Tax", "Availed ITC State/UT Tax", "Availed ITC Cess"
            )
            val rows = report.b2bur.map {
                arrayOf(
                    it.supplierName, it.invoiceNumber, it.invoiceDate, it.invoiceValue.toString(),
                    it.placeOfSupply, it.supplyType, it.rate.toString(), it.taxableValue.toString(),
                    it.igstPaid.toString(), it.cgstPaid.toString(), it.sgstPaid.toString(),
                    it.cessPaid.toString(), it.eligibilityForItc, it.availedItcIgst.toString(),
                    it.availedItcCgst.toString(), it.availedItcSgst.toString(), it.availedItcCess.toString()
                )
            }
            writeCsv("b2bur", headers, rows)
        }

        // IMPS
        if (report.imps.isNotEmpty()) {
            val headers = arrayOf(
                "Invoice Number", "Invoice date", "Invoice Value", "Place Of Supply", "Rate",
                "Taxable Value", "Integrated Tax Paid", "Cess Paid", "Eligibility For ITC",
                "Availed ITC Integrated Tax", "Availed ITC Cess"
            )
            val rows = report.imps.map {
                arrayOf(
                    it.invoiceNumber, it.invoiceDate, it.invoiceValue.toString(), it.placeOfSupply,
                    it.rate.toString(), it.taxableValue.toString(), it.igstPaid.toString(),
                    it.cessPaid.toString(), it.eligibilityForItc, it.availedItcIgst.toString(),
                    it.availedItcCess.toString()
                )
            }
            writeCsv("imps", headers, rows)
        }

        // IMPG
        if (report.impg.isNotEmpty()) {
            val headers = arrayOf(
                "Port Code", "Bill Of Entry Number", "Bill Of Entry Date", "Bill Of Entry Value",
                "Document type", "GSTIN Of SEZ Supplier", "Rate", "Taxable Value", "Integrated Tax Paid",
                "Cess Paid", "Eligibility For ITC", "Availed ITC Integrated Tax", "Availed ITC Cess"
            )
            val rows = report.impg.map {
                arrayOf(
                    it.portCode, it.billOfEntryNumber, it.billOfEntryDate, it.billOfEntryValue.toString(),
                    it.documentType, it.sezSupplierGstin, it.rate.toString(), it.taxableValue.toString(),
                    it.igstPaid.toString(), it.cessPaid.toString(), it.eligibilityForItc,
                    it.availedItcIgst.toString(), it.availedItcCess.toString()
                )
            }
            writeCsv("impg", headers, rows)
        }

        // CDNR
        if (report.cdnr.isNotEmpty()) {
            val headers = arrayOf(
                "GSTIN of Supplier", "Note/Refund Voucher Number", "Note/Refund Voucher date",
                "Invoice/Advance Payment Voucher Number", "Invoice/Advance Payment Voucher date",
                "Pre GST", "Document Type", "Reason For Issuing document", "Supply Type",
                "Note/Refund Voucher Value", "Rate", "Taxable Value", "Integrated Tax Paid",
                "Central Tax Paid", "State/UT Tax Paid", "Cess Paid", "Eligibility For ITC",
                "Availed ITC Integrated Tax", "Availed ITC Central Tax", "Availed ITC State/UT Tax",
                "Availed ITC Cess"
            )
            val rows = report.cdnr.map {
                arrayOf(
                    it.supplierGstin, it.noteNumber, it.noteDate, it.invoiceNumber, it.invoiceDate,
                    it.preGst, it.documentType, it.reason, it.supplyType, it.noteValue.toString(),
                    it.rate.toString(), it.taxableValue.toString(), it.igstPaid.toString(),
                    it.cgstPaid.toString(), it.sgstPaid.toString(), it.cessPaid.toString(),
                    it.eligibilityForItc, it.availedItcIgst.toString(), it.availedItcCgst.toString(),
                    it.availedItcSgst.toString(), it.availedItcCess.toString()
                )
            }
            writeCsv("cdnr", headers, rows)
        }

        // CDNUR
        if (report.cdnur.isNotEmpty()) {
            val headers = arrayOf(
                "Note/Refund Voucher Number", "Note/Refund Voucher date",
                "Invoice/Advance Payment Voucher Number", "Invoice/Advance Payment Voucher date",
                "Pre GST", "Document Type", "Reason For Issuing document", "Supply Type",
                "Invoice Type", "Note/Refund Voucher Value", "Rate", "Taxable Value",
                "Integrated Tax Paid", "Central Tax Paid", "State/UT Tax Paid", "Cess Paid",
                "Eligibility For ITC", "Availed ITC Integrated Tax", "Availed ITC Central Tax",
                "Availed ITC State/UT Tax", "Availed ITC Cess"
            )
            val rows = report.cdnur.map {
                arrayOf(
                    it.noteNumber, it.noteDate, it.invoiceNumber, it.invoiceDate, it.preGst,
                    it.documentType, it.reason, it.supplyType, it.invoiceType, it.noteValue.toString(),
                    it.rate.toString(), it.taxableValue.toString(), it.igstPaid.toString(),
                    it.cgstPaid.toString(), it.sgstPaid.toString(), it.cessPaid.toString(),
                    it.eligibilityForItc, it.availedItcIgst.toString(), it.availedItcCgst.toString(),
                    it.availedItcSgst.toString(), it.availedItcCess.toString()
                )
            }
            writeCsv("cdnur", headers, rows)
        }

        // EXEMP
        if (report.exemp.isNotEmpty()) {
            val headers = arrayOf(
                "Description", "Composition taxable person", "Nil Rated Supplies",
                "Exempted (other than nil rated/non GST supply )", "Non-GST supplies"
            )
            val rows = report.exemp.map {
                arrayOf(
                    it.description, it.composition.toString(), it.nilRated.toString(),
                    it.exempted.toString(), it.nonGst.toString()
                )
            }
            writeCsv("exemp", headers, rows)
        }

        // HSNSUM
        if (report.hsnsum.isNotEmpty()) {
            val headers = arrayOf(
                "HSN", "Description", "UQC", "Total Quantity", "Total Value", "Taxable Value",
                "Integrated Tax Amount", "Central Tax Amount", "State/UT Tax Amount", "Cess Amount"
            )
            val rows = report.hsnsum.map {
                arrayOf(
                    it.hsn, it.description, it.uqc, it.totalQuantity.toString(),
                    it.totalValue.toString(), it.taxableValue.toString(), it.igstAmount.toString(),
                    it.cgstAmount.toString(), it.sgstAmount.toString(), it.cessAmount.toString()
                )
            }
            writeCsv("hsnsum", headers, rows)
        }

        return ExportResult(files, exportDir)
    }
}
