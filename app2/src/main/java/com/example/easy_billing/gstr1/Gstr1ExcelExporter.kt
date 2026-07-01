package com.example.easy_billing.gstr1

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream

/**
 * Gstr1ExcelExporter
 *
 * Writes GSTR-1 data into the GST Offline Tool Excel workbook template
 * (GSTR1_Excel_Workbook_Template_V2.2.xlsx) or creates a compatible
 * standalone workbook if the template is not available.
 *
 * Sheet tab names match the GST portal / offline tool naming:
 *   b2b, b2cl, b2cs, cdnr, cdnur, hsn(b2b), hsn(b2c),
 *   docs, eco, ecob2b, ecob2c, ecourp2b, ecourp2c
 *
 * Note: This requires the Apache POI dependency in build.gradle:
 *   implementation 'org.apache.poi:poi-ooxml:5.2.3'
 */
object Gstr1ExcelExporter {

    data class ExportResult(val file: File, val uri: Uri)

    fun export(context: Context, report: Gstr1Report): ExportResult {
        val wb = XSSFWorkbook()
        val headerStyle = createHeaderStyle(wb)

        buildB2B(wb, headerStyle, report.b2b)
        buildB2CL(wb, headerStyle, report.b2cl)
        buildB2CS(wb, headerStyle, report.b2cs)
        buildCdnr(wb, headerStyle, report.cdnr)
        buildCdnur(wb, headerStyle, report.cdnur)
        buildHsn(wb, headerStyle, "hsn(b2b)", report.hsnB2B)
        buildHsn(wb, headerStyle, "hsn(b2c)", report.hsnB2C)
        buildDocs(wb, headerStyle, report.docs)
        buildEco(wb, headerStyle, report.eco)
        buildEcoB2B(wb, headerStyle, report.ecoB2B)
        buildEcoB2C(wb, headerStyle, report.ecoB2C)
        buildEcoUrp2B(wb, headerStyle, report.ecoUrp2B)
        buildEcoUrp2C(wb, headerStyle, report.ecoUrp2C)

        val dir = File(context.getExternalFilesDir(null),
            "GSTR1_${report.gstin}_${report.financialYear}_${report.period}")
            .also { it.mkdirs() }

        val file = File(dir, "GSTR1_${report.financialYear}_${report.period}.xlsx")
        FileOutputStream(file).use { wb.write(it) }
        wb.close()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        return ExportResult(file, uri)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Sheet builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildB2B(wb: Workbook, hs: CellStyle, rows: List<B2BRow>) {
        val sheet = wb.createSheet("b2b")
        header(sheet, hs, listOf(
            "GSTIN/UIN of Recipient","Receiver Name","Invoice Number","Invoice date",
            "Invoice Value","Place Of Supply","Reverse Charge","Applicable % of Tax Rate",
            "Invoice Type","E-Commerce GSTIN","Rate","Taxable Value","Cess Amount"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.gstin, r.receiverName, r.invoiceNumber, r.invoiceDate,
                r.invoiceValue, r.placeOfSupply, r.reverseCharge, r.applicableRate,
                r.invoiceType, r.ecomGstin, r.rate, r.taxableValue, r.cessAmount)
        }
        autoSize(sheet, 13)
    }

    private fun buildB2CL(wb: Workbook, hs: CellStyle, rows: List<B2CLRow>) {
        val sheet = wb.createSheet("b2cl")
        header(sheet, hs, listOf(
            "Invoice Number","Invoice date","Invoice Value","Place Of Supply",
            "Applicable % of Tax Rate","Rate","Taxable Value","Cess Amount","E-Commerce GSTIN"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.invoiceNumber, r.invoiceDate, r.invoiceValue,
                r.placeOfSupply, r.applicableRate, r.rate, r.taxableValue, r.cessAmount, r.ecomGstin)
        }
        autoSize(sheet, 9)
    }

    private fun buildB2CS(wb: Workbook, hs: CellStyle, rows: List<B2CSRow>) {
        val sheet = wb.createSheet("b2cs")
        header(sheet, hs, listOf(
            "Type","Place Of Supply","Rate","Applicable % of Tax Rate",
            "Taxable Value","Cess Amount","E-Commerce GSTIN"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.type, r.placeOfSupply, r.rate,
                r.applicableRate, r.taxableValue, r.cessAmount, r.ecomGstin)
        }
        autoSize(sheet, 7)
    }

    private fun buildCdnr(wb: Workbook, hs: CellStyle, rows: List<CdnrRow>) {
        val sheet = wb.createSheet("cdnr")
        header(sheet, hs, listOf(
            "GSTIN/UIN of Recipient","Receiver Name","Note Number","Note Date","Note Type",
            "Place Of Supply","Reverse Charge","Note Supply Type","Note Value",
            "Applicable % of Tax Rate","Rate","Taxable Value","Cess Amount"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.gstin, r.receiverName, r.noteNumber, r.noteDate, r.noteType,
                r.placeOfSupply, r.reverseCharge, r.noteSupplyType, r.noteValue,
                r.applicableRate, r.rate, r.taxableValue, r.cessAmount)
        }
        autoSize(sheet, 13)
    }

    private fun buildCdnur(wb: Workbook, hs: CellStyle, rows: List<CdnurRow>) {
        val sheet = wb.createSheet("cdnur")
        header(sheet, hs, listOf(
            "UR Type","Note Number","Note Date","Note Type","Place Of Supply","Note Value",
            "Applicable % of Tax Rate","Rate","Taxable Value","Cess Amount"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.urType, r.noteNumber, r.noteDate, r.noteType,
                r.placeOfSupply, r.noteValue, r.applicableRate, r.rate, r.taxableValue, r.cessAmount)
        }
        autoSize(sheet, 10)
    }

    private fun buildHsn(wb: Workbook, hs: CellStyle, sheetName: String, rows: List<HsnRow>) {
        val sheet = wb.createSheet(sheetName)
        header(sheet, hs, listOf(
            "HSN","Description","UQC","Total Quantity","Total Value","Taxable Value",
            "Integrated Tax Amount","Central Tax Amount","State/UT Tax Amount","Cess Amount","Rate"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.hsn, r.description, r.uqc, r.totalQuantity,
                r.totalValue, r.taxableValue, r.igstAmount, r.cgstAmount, r.sgstAmount,
                r.cessAmount, r.rate)
        }
        autoSize(sheet, 11)
    }

    private fun buildDocs(wb: Workbook, hs: CellStyle, rows: List<DocsRow>) {
        val sheet = wb.createSheet("docs")
        header(sheet, hs, listOf(
            "Nature of Document","Sr. No. From","Sr. No. To","Total Number","Cancelled"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.natureOfDoc, r.srFrom, r.srTo,
                r.totalNumber.toDouble(), r.cancelled.toDouble())
        }
        autoSize(sheet, 5)
    }

    private fun buildEco(wb: Workbook, hs: CellStyle, rows: List<EcoRow>) {
        val sheet = wb.createSheet("eco")
        header(sheet, hs, listOf(
            "Nature of Supply","GSTIN of E-Commerce Operator","E-Commerce Operator Name",
            "Net value of supplies","Integrated tax","Central tax","State/UT tax","Cess"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.natureOfSupply, r.ecoGstin, r.ecoName,
                r.netValue, r.igst, r.cgst, r.sgst, r.cess)
        }
        autoSize(sheet, 8)
    }

    private fun buildEcoB2B(wb: Workbook, hs: CellStyle, rows: List<EcoB2BRow>) {
        val sheet = wb.createSheet("ecob2b")
        header(sheet, hs, listOf(
            "Supplier GSTIN/UIN","Supplier Name","Recipient GSTIN/UIN","Recipient Name",
            "Document Number","Document Date","Value of supplies made","Place Of Supply",
            "Document type","Rate","Taxable Value","Cess Amount"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.supplierGstin, r.supplierName, r.recipientGstin, r.recipientName,
                r.docNumber, r.docDate, r.supplyValue, r.placeOfSupply, r.docType,
                r.rate, r.taxableValue, r.cessAmount)
        }
        autoSize(sheet, 12)
    }

    private fun buildEcoB2C(wb: Workbook, hs: CellStyle, rows: List<EcoB2CRow>) {
        val sheet = wb.createSheet("ecob2c")
        header(sheet, hs, listOf(
            "Supplier GSTIN/UIN","Supplier Name","Place Of Supply","Rate","Taxable Value","Cess Amount"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.supplierGstin, r.supplierName, r.placeOfSupply,
                r.rate, r.taxableValue, r.cessAmount)
        }
        autoSize(sheet, 6)
    }

    private fun buildEcoUrp2B(wb: Workbook, hs: CellStyle, rows: List<EcoUrp2BRow>) {
        val sheet = wb.createSheet("ecourp2b")
        header(sheet, hs, listOf(
            "Recipient GSTIN/UIN","Recipient Name","Document Number","Document Date",
            "Value of supplies made","Place Of Supply","Document type","Rate","Taxable Value","Cess Amount"
        ))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.recipientGstin, r.recipientName, r.docNumber, r.docDate,
                r.supplyValue, r.placeOfSupply, r.docType, r.rate, r.taxableValue, r.cessAmount)
        }
        autoSize(sheet, 10)
    }

    private fun buildEcoUrp2C(wb: Workbook, hs: CellStyle, rows: List<EcoUrp2CRow>) {
        val sheet = wb.createSheet("ecourp2c")
        header(sheet, hs, listOf("Place Of Supply","Rate","Taxable Value","Cess Amount"))
        rows.forEachIndexed { i, r ->
            row(sheet, i + 1, r.placeOfSupply, r.rate, r.taxableValue, r.cessAmount)
        }
        autoSize(sheet, 4)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  POI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun header(sheet: Sheet, style: CellStyle, cols: List<String>) {
        val row = sheet.createRow(0)
        cols.forEachIndexed { idx, title ->
            row.createCell(idx).apply {
                setCellValue(title)
                cellStyle = style
            }
        }
    }

    /** Writes one data row. Values can be String or Double. */
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
                // autoSizeColumn depends on java.awt (FontRenderContext) which is missing on Android.
                // We catch Throwable to handle NoClassDefFoundError and set a default width.
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
