package com.example.easy_billing.gstr2

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream

object Gstr2ExcelExporter {

    data class ExportResult(val file: File, val uri: Uri)

    fun export(context: Context, report: Gstr2Report): ExportResult {
        val wb = XSSFWorkbook()
        val headerStyle = createHeaderStyle(wb)

        buildB2B(wb, headerStyle, report.b2b)
        buildB2BUR(wb, headerStyle, report.b2bur)
        buildIMPS(wb, headerStyle, report.imps)
        buildIMPG(wb, headerStyle, report.impg)
        buildCDNR(wb, headerStyle, report.cdnr)
        buildCDNUR(wb, headerStyle, report.cdnur)
        buildEXEMP(wb, headerStyle, report.exemp)
        buildHSNSUM(wb, headerStyle, report.hsnsum)

        val dir = File(context.getExternalFilesDir(null),
            "GSTR2_${report.gstin}_${report.financialYear}_${report.period}")
            .also { it.mkdirs() }

        val file = File(dir, "GSTR2_${report.financialYear}_${report.period}.xlsx")
        FileOutputStream(file).use { wb.write(it) }
        wb.close()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        return ExportResult(file, uri)
    }

    private fun buildB2B(wb: Workbook, hs: CellStyle, rows: List<Gstr2B2bRow>) {
        val sheet = wb.createSheet("b2b")
        header(sheet, hs, listOf(
            "GSTIN of Supplier", "Invoice Number", "Invoice date", "Invoice Value",
            "Place Of Supply", "Reverse Charge", "Invoice Type", "Rate", "Taxable Value",
            "Integrated Tax Paid", "Central Tax Paid", "State/UT Tax Paid", "Cess Paid",
            "Eligibility For ITC", "Availed ITC Integrated Tax", "Availed ITC Central Tax",
            "Availed ITC State/UT Tax", "Availed ITC Cess"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.supplierGstin, r.invoiceNumber, r.invoiceDate, r.invoiceValue,
                r.placeOfSupply, r.reverseCharge, r.invoiceType, r.rate, r.taxableValue,
                r.igstPaid, r.cgstPaid, r.sgstPaid, r.cessPaid, r.eligibilityForItc,
                r.availedItcIgst, r.availedItcCgst, r.availedItcSgst, r.availedItcCess)
        }
        autoSize(sheet, 18)
    }

    private fun buildB2BUR(wb: Workbook, hs: CellStyle, rows: List<Gstr2B2burRow>) {
        val sheet = wb.createSheet("b2bur")
        header(sheet, hs, listOf(
            "Supplier Name", "Invoice Number", "Invoice date", "Invoice Value", "Place Of Supply",
            "Supply Type", "Rate", "Taxable Value", "Integrated Tax Paid", "Central Tax Paid",
            "State/UT Tax Paid", "Cess Paid", "Eligibility For ITC", "Availed ITC Integrated Tax",
            "Availed ITC Central Tax", "Availed ITC State/UT Tax", "Availed ITC Cess"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.supplierName, r.invoiceNumber, r.invoiceDate, r.invoiceValue,
                r.placeOfSupply, r.supplyType, r.rate, r.taxableValue, r.igstPaid, r.cgstPaid,
                r.sgstPaid, r.cessPaid, r.eligibilityForItc, r.availedItcIgst, r.availedItcCgst,
                r.availedItcSgst, r.availedItcCess)
        }
        autoSize(sheet, 17)
    }

    private fun buildIMPS(wb: Workbook, hs: CellStyle, rows: List<Gstr2ImpsRow>) {
        val sheet = wb.createSheet("imps")
        header(sheet, hs, listOf(
            "Invoice Number", "Invoice date", "Invoice Value", "Place Of Supply", "Rate",
            "Taxable Value", "Integrated Tax Paid", "Cess Paid", "Eligibility For ITC",
            "Availed ITC Integrated Tax", "Availed ITC Cess"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.invoiceNumber, r.invoiceDate, r.invoiceValue, r.placeOfSupply,
                r.rate, r.taxableValue, r.igstPaid, r.cessPaid, r.eligibilityForItc,
                r.availedItcIgst, r.availedItcCess)
        }
        autoSize(sheet, 11)
    }

    private fun buildIMPG(wb: Workbook, hs: CellStyle, rows: List<Gstr2ImpgRow>) {
        val sheet = wb.createSheet("impg")
        header(sheet, hs, listOf(
            "Port Code", "Bill Of Entry Number", "Bill Of Entry Date", "Bill Of Entry Value",
            "Document type", "GSTIN Of SEZ Supplier", "Rate", "Taxable Value", "Integrated Tax Paid",
            "Cess Paid", "Eligibility For ITC", "Availed ITC Integrated Tax", "Availed ITC Cess"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.portCode, r.billOfEntryNumber, r.billOfEntryDate, r.billOfEntryValue,
                r.documentType, r.sezSupplierGstin, r.rate, r.taxableValue, r.igstPaid, r.cessPaid,
                r.eligibilityForItc, r.availedItcIgst, r.availedItcCess)
        }
        autoSize(sheet, 13)
    }

    private fun buildCDNR(wb: Workbook, hs: CellStyle, rows: List<Gstr2CdnrRow>) {
        val sheet = wb.createSheet("cdnr")
        header(sheet, hs, listOf(
            "GSTIN of Supplier", "Note/Refund Voucher Number", "Note/Refund Voucher date",
            "Invoice/Advance Payment Voucher Number", "Invoice/Advance Payment Voucher date",
            "Pre GST", "Document Type", "Reason For Issuing document", "Supply Type",
            "Note/Refund Voucher Value", "Rate", "Taxable Value", "Integrated Tax Paid",
            "Central Tax Paid", "State/UT Tax Paid", "Cess Paid", "Eligibility For ITC",
            "Availed ITC Integrated Tax", "Availed ITC Central Tax", "Availed ITC State/UT Tax",
            "Availed ITC Cess"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.supplierGstin, r.noteNumber, r.noteDate, r.invoiceNumber,
                r.invoiceDate, r.preGst, r.documentType, r.reason, r.supplyType, r.noteValue,
                r.rate, r.taxableValue, r.igstPaid, r.cgstPaid, r.sgstPaid, r.cessPaid,
                r.eligibilityForItc, r.availedItcIgst, r.availedItcCgst, r.availedItcSgst,
                r.availedItcCess)
        }
        autoSize(sheet, 21)
    }

    private fun buildCDNUR(wb: Workbook, hs: CellStyle, rows: List<Gstr2CdnurRow>) {
        val sheet = wb.createSheet("cdnur")
        header(sheet, hs, listOf(
            "Note/Refund Voucher Number", "Note/Refund Voucher date",
            "Invoice/Advance Payment Voucher Number", "Invoice/Advance Payment Voucher date",
            "Pre GST", "Document Type", "Reason For Issuing document", "Supply Type",
            "Invoice Type", "Note/Refund Voucher Value", "Rate", "Taxable Value",
            "Integrated Tax Paid", "Central Tax Paid", "State/UT Tax Paid", "Cess Paid",
            "Eligibility For ITC", "Availed ITC Integrated Tax", "Availed ITC Central Tax",
            "Availed ITC State/UT Tax", "Availed ITC Cess"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.noteNumber, r.noteDate, r.invoiceNumber, r.invoiceDate,
                r.preGst, r.documentType, r.reason, r.supplyType, r.invoiceType, r.noteValue,
                r.rate, r.taxableValue, r.igstPaid, r.cgstPaid, r.sgstPaid, r.cessPaid,
                r.eligibilityForItc, r.availedItcIgst, r.availedItcCgst, r.availedItcSgst,
                r.availedItcCess)
        }
        autoSize(sheet, 21)
    }

    private fun buildEXEMP(wb: Workbook, hs: CellStyle, rows: List<Gstr2ExempRow>) {
        val sheet = wb.createSheet("exemp")
        header(sheet, hs, listOf(
            "Description", "Composition taxable person", "Nil Rated Supplies",
            "Exempted (other than nil rated/non GST supply )", "Non-GST supplies"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.description, r.composition, r.nilRated, r.exempted, r.nonGst)
        }
        autoSize(sheet, 5)
    }

    private fun buildHSNSUM(wb: Workbook, hs: CellStyle, rows: List<Gstr2HsnsumRow>) {
        val sheet = wb.createSheet("hsnsum")
        header(sheet, hs, listOf(
            "HSN", "Description", "UQC", "Total Quantity", "Total Value", "Taxable Value",
            "Integrated Tax Amount", "Central Tax Amount", "State/UT Tax Amount", "Cess Amount"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.hsn, r.description, r.uqc, r.totalQuantity, r.totalValue,
                r.taxableValue, r.igstAmount, r.cgstAmount, r.sgstAmount, r.cessAmount)
        }
        autoSize(sheet, 10)
    }

    private fun header(sheet: Sheet, style: CellStyle, cols: List<String>) {
        val row = sheet.createRow(0)
        cols.forEachIndexed { idx, title ->
            row.createCell(idx).apply {
                setCellValue(title)
                cellStyle = style
            }
        }
    }

    private fun row(sheet: Sheet, rowIdx: Int, vararg values: Any) {
        val row = sheet.createRow(rowIdx)
        values.forEachIndexed { col, v ->
            val cell = row.createCell(col)
            when (v) {
                is Double -> cell.setCellValue(v)
                is Int    -> cell.setCellValue(v.toDouble())
                else      -> cell.setCellValue(v.toString())
            }
        }
    }

    private fun autoSize(sheet: Sheet, colCount: Int) {
        for (i in 0 until colCount) {
            try {
                sheet.autoSizeColumn(i)
            } catch (e: Throwable) {
                sheet.setColumnWidth(i, 4500)
            }
        }
    }

    private fun createHeaderStyle(wb: Workbook): CellStyle {
        val font = wb.createFont().apply {
            bold = true
            fontHeightInPoints = 10
        }
        return wb.createCellStyle().apply {
            setFont(font)
            fillForegroundColor = IndexedColors.LIGHT_CORNFLOWER_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            borderBottom = BorderStyle.THIN
        }
    }
}
