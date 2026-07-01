package com.example.easy_billing.gstr1

import com.example.easy_billing.db.CreditNote
import com.example.easy_billing.db.CreditNoteItem
import com.example.easy_billing.db.GstSalesInvoice
import com.example.easy_billing.db.GstSalesInvoiceItem
import com.example.easy_billing.gstr1.Gstr1Repository.InvoiceWithItems
import com.example.easy_billing.gstr1.Gstr1Repository.CreditNoteWithItems
import com.example.easy_billing.gstr1.Gstr1Repository.RawGstr1Data
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Gstr1Generator
 *
 * Classifies raw DB rows into GSTR-1 sheet models.
 *
 * Classification rules:
 *
 *  B2B   — invoiceType == "B2B" (customer has GSTIN), NOT cancelled
 *  B2CL  — invoiceType == "B2C", inter-state supply, grandTotal > ₹2,50,000
 *           (This threshold applies for invoices issued before Aug 2024.
 *            The code uses ₹2,50,000 consistently as per RetailNotes 3.2.1.)
 *  B2CS  — all other non-cancelled B2C (intra-state OR small inter-state)
 *
 *  CDNR  — credit/debit notes where customerGstin is not blank
 *  CDNUR — credit/debit notes where customerGstin IS blank
 *
 *  HSN(B2B) — HSN summary from B2B items only
 *  HSN(B2C) — HSN summary from B2CL + B2CS items
 *
 *  DOCS  — document series summary from all non-cancelled invoices + credit notes
 *
 *  ECO   — invoices/notes where ecoRole is set
 */
object Gstr1Generator {

    private val dateFmt = SimpleDateFormat("dd-MMM-yy", Locale.getDefault())
    private const val B2CL_THRESHOLD = 250_000.0

    // ─────────────────────────────────────────────────────────────────────────

    fun generate(
        data: RawGstr1Data,
        financialYear: String,
        period: String,
        returnType: String
    ): Gstr1Report {

        val gstin       = data.profile?.gstin ?: ""
        val shopState   = data.profile?.stateCode ?: ""

        // Separate cancelled from active invoices
        val activeInvoices    = data.invoices.filter { !it.invoice.isCancelled }
        val cancelledInvoices = data.invoices.filter { it.invoice.isCancelled }

        // ── Classify B2B / B2CL / B2CS ─────────────────────────────────────
        val b2bInvoices  = activeInvoices.filter { it.invoice.invoiceType == "B2B" }
        val b2cInvoices  = activeInvoices.filter { it.invoice.invoiceType != "B2B" }

        val b2clInvoices = b2cInvoices.filter { iw ->
            val placeCode = iw.invoice.customerStateCode ?: ""
            val isInterState = placeCode.isNotBlank() && placeCode != shopState
            isInterState && iw.invoice.grandTotal > B2CL_THRESHOLD
        }
        val b2clNumbers  = b2clInvoices.map { it.invoice.invoiceNumber }.toSet()
        val b2csInvoices = b2cInvoices.filter { it.invoice.invoiceNumber !in b2clNumbers }

        // ── B2B rows ────────────────────────────────────────────────────────
        val b2bRows = mutableListOf<B2BRow>()
        for (iw in b2bInvoices) {
            val inv   = iw.invoice
            val byRate = iw.items.groupBy { effectiveRate(it) }
            for ((rate, items) in byRate) {
                b2bRows.add(B2BRow(
                    gstin          = inv.customerGst ?: "",
                    receiverName   = inv.businessName ?: inv.customerName ?: "",
                    invoiceNumber  = inv.invoiceNumber,
                    invoiceDate    = formatDate(inv.invoiceDate),
                    invoiceValue   = inv.grandTotal,
                    placeOfSupply  = formatPos(inv.customerStateCode, inv.customerState ?: ""),
                    reverseCharge  = inv.reverseCharge,
                    applicableRate = "",
                    invoiceType    = mapGstrInvoiceType(inv.gstrInvoiceType),
                    ecomGstin      = inv.ecommerceGstin ?: "",
                    rate           = rate,
                    taxableValue   = items.sumOf { it.taxableAmount },
                    cessAmount     = items.sumOf { it.cessAmount }
                ))
            }
        }

        // ── B2CL rows ────────────────────────────────────────────────────────
        val b2clRows = mutableListOf<B2CLRow>()
        for (iw in b2clInvoices) {
            val inv   = iw.invoice
            val byRate = iw.items.groupBy { effectiveRate(it) }
            for ((rate, items) in byRate) {
                b2clRows.add(B2CLRow(
                    invoiceNumber  = inv.invoiceNumber,
                    invoiceDate    = formatDate(inv.invoiceDate),
                    invoiceValue   = inv.grandTotal,
                    placeOfSupply  = formatPos(inv.customerStateCode, inv.customerState ?: ""),
                    applicableRate = "",
                    rate           = rate,
                    taxableValue   = items.sumOf { it.taxableAmount },
                    cessAmount     = items.sumOf { it.cessAmount },
                    ecomGstin      = inv.ecommerceGstin ?: ""
                ))
            }
        }

        // ── B2CS rows (aggregate per POS per rate) ──────────────────────────
        data class B2CSKey(val pos: String, val rate: Double, val ecomGstin: String)
        val b2csAgg = mutableMapOf<B2CSKey, Triple<Double, Double, Boolean>>() // taxable, cess, isEcom
        for (iw in b2csInvoices) {
            val inv = iw.invoice
            val pos = formatPos(inv.customerStateCode, inv.customerState ?: "")
            val ecom = inv.ecommerceGstin ?: ""
            for (item in iw.items) {
                val rate = effectiveRate(item)
                val key  = B2CSKey(pos, rate, ecom)
                val (prevTax, prevCess, _) = b2csAgg.getOrDefault(key, Triple(0.0, 0.0, ecom.isNotBlank()))
                b2csAgg[key] = Triple(prevTax + item.taxableAmount, prevCess + item.cessAmount, ecom.isNotBlank())
            }
        }
        val b2csRows = b2csAgg.map { (key, v) ->
            B2CSRow(
                type          = if (v.third) "E" else "OE",
                placeOfSupply = key.pos,
                rate          = key.rate,
                applicableRate = "",
                taxableValue  = v.first,
                cessAmount    = v.second,
                ecomGstin     = key.ecomGstin
            )
        }

        // ── CDNR / CDNUR ────────────────────────────────────────────────────
        val cdnrRows  = mutableListOf<CdnrRow>()
        val cdnurRows = mutableListOf<CdnurRow>()

        for (nw in data.creditNotes) {
            val note = nw.note
            val noteDate = formatDate(note.noteDate)
            val pos = note.placeOfSupply

            if (!note.customerGstin.isNullOrBlank()) {
                // Registered — group by rate
                val byRate = nw.items.groupBy { it.gstRate }
                for ((rate, items) in byRate) {
                    cdnrRows.add(CdnrRow(
                        gstin          = note.customerGstin,
                        receiverName   = note.customerName,
                        noteNumber     = note.noteNumber,
                        noteDate       = noteDate,
                        noteType       = note.noteType,
                        placeOfSupply  = pos,
                        reverseCharge  = note.reverseCharge,
                        noteSupplyType = note.noteSupplyType,
                        noteValue      = note.totalAmount,
                        applicableRate = "",
                        rate           = rate,
                        taxableValue   = items.sumOf { it.taxableValue },
                        cessAmount     = items.sumOf { it.cessAmount }
                    ))
                }
            } else {
                // Unregistered — group by rate
                val byRate = nw.items.groupBy { it.gstRate }
                for ((rate, items) in byRate) {
                    cdnurRows.add(CdnurRow(
                        urType        = note.urType,
                        noteNumber    = note.noteNumber,
                        noteDate      = noteDate,
                        noteType      = note.noteType,
                        placeOfSupply = pos,
                        noteValue     = note.totalAmount,
                        applicableRate = "",
                        rate          = rate,
                        taxableValue  = items.sumOf { it.taxableValue },
                        cessAmount    = items.sumOf { it.cessAmount }
                    ))
                }
            }
        }

        // ── HSN rows ─────────────────────────────────────────────────────────
        data class HsnKey(val hsn: String, val uqc: String, val rate: Double, val description: String)
        data class HsnAgg(var qty: Double = 0.0, var value: Double = 0.0,
                          var taxable: Double = 0.0, var igst: Double = 0.0,
                          var cgst: Double = 0.0, var sgst: Double = 0.0,
                          var cess: Double = 0.0)

        val hsnB2BAgg  = mutableMapOf<HsnKey, HsnAgg>()
        val hsnB2CAgg  = mutableMapOf<HsnKey, HsnAgg>()

        fun addToHsnAgg(agg: MutableMap<HsnKey, HsnAgg>, inv: GstSalesInvoice, items: List<GstSalesInvoiceItem>) {
            for (item in items) {
                val key = HsnKey(
                    hsn         = item.hsnCode.ifBlank { "N/A" },
                    uqc         = item.uqc ?: "NOS",
                    rate        = effectiveRate(item),
                    description = item.hsnDescription ?: item.productName
                )
                val a = agg.getOrPut(key) { HsnAgg() }
                a.qty    += item.quantity
                a.value  += item.netValue
                a.taxable += item.taxableAmount
                a.igst   += item.igstAmount
                a.cgst   += item.cgstAmount
                a.sgst   += item.sgstAmount
                a.cess   += item.cessAmount
            }
        }

        for (iw in b2bInvoices)  addToHsnAgg(hsnB2BAgg, iw.invoice, iw.items)
        for (iw in b2clInvoices) addToHsnAgg(hsnB2CAgg, iw.invoice, iw.items)
        for (iw in b2csInvoices) addToHsnAgg(hsnB2CAgg, iw.invoice, iw.items)

        fun aggToHsnRows(agg: Map<HsnKey, HsnAgg>) = agg.map { (key, v) ->
            HsnRow(
                hsn           = key.hsn,
                description   = key.description,
                uqc           = key.uqc,
                totalQuantity = v.qty,
                totalValue    = v.value,
                taxableValue  = v.taxable,
                igstAmount    = v.igst,
                cgstAmount    = v.cgst,
                sgstAmount    = v.sgst,
                cessAmount    = v.cess,
                rate          = key.rate
            )
        }

        val hsnB2BRows = aggToHsnRows(hsnB2BAgg)
        val hsnB2CRows = aggToHsnRows(hsnB2CAgg)

        // ── DOCS rows ─────────────────────────────────────────────────────────
        // Group invoices by document series and document nature
        data class DocsKey(val series: String, val nature: String)
        data class DocsAgg(val numbers: MutableList<String> = mutableListOf(), var cancelled: Int = 0)

        val docsAgg = mutableMapOf<DocsKey, DocsAgg>()
        for (iw in data.invoices) {
            val inv = iw.invoice
            val key = DocsKey(inv.documentSeries.ifBlank { "INV" }, inv.documentNature.ifBlank { "Invoices for outward supply" })
            val agg = docsAgg.getOrPut(key) { DocsAgg() }
            agg.numbers.add(inv.invoiceNumber)
            if (inv.isCancelled) agg.cancelled++
        }
        // Credit notes series
        for (nw in data.creditNotes) {
            val note = nw.note
            val series = note.documentSeries.ifBlank { if (note.noteType == "C") "CN" else "DN" }
            val nature = note.documentNature.ifBlank {
                if (note.noteType == "C") "Credit Notes" else "Debit Notes"
            }
            val key = DocsKey(series, nature)
            val agg = docsAgg.getOrPut(key) { DocsAgg() }
            agg.numbers.add(note.noteNumber)
        }

        val docsRows = docsAgg.mapNotNull { (key, agg) ->
            if (agg.numbers.isEmpty()) null
            else {
                val sorted = agg.numbers.sorted()
                DocsRow(
                    natureOfDoc = key.nature,
                    srFrom      = sorted.first(),
                    srTo        = sorted.last(),
                    totalNumber = agg.numbers.size,
                    cancelled   = agg.cancelled
                )
            }
        }

        // ── ECO tables ────────────────────────────────────────────────────────
        val ecoInvoices = activeInvoices.filter { !it.invoice.ecoRole.isNullOrBlank() }

        // ECO aggregate
        data class EcoKey(val ecoGstin: String, val ecoName: String, val nature: String)
        data class EcoAggVal(var net: Double = 0.0, var igst: Double = 0.0,
                             var cgst: Double = 0.0, var sgst: Double = 0.0, var cess: Double = 0.0)
        val ecoAgg = mutableMapOf<EcoKey, EcoAggVal>()
        for (iw in ecoInvoices) {
            val inv = iw.invoice
            val key = EcoKey(
                ecoGstin = inv.ecommerceGstin ?: "",
                ecoName  = inv.ecommerceOperatorName ?: "",
                nature   = inv.ecoNatureOfSupply ?: "B2C"
            )
            val a = ecoAgg.getOrPut(key) { EcoAggVal() }
            a.net  += inv.grandTotal
            a.igst += inv.totalIgst
            a.cgst += inv.totalCgst
            a.sgst += inv.totalSgst
        }
        val ecoRows = ecoAgg.map { (k, v) ->
            EcoRow(natureOfSupply = k.nature, ecoGstin = k.ecoGstin, ecoName = k.ecoName,
                netValue = v.net, igst = v.igst, cgst = v.cgst, sgst = v.sgst, cess = 0.0)
        }

        // ECO B2B / B2C / URP
        val ecoB2BRows  = mutableListOf<EcoB2BRow>()
        val ecoB2CRows  = mutableListOf<EcoB2CRow>()
        val ecoUrp2BRows = mutableListOf<EcoUrp2BRow>()
        val ecoUrp2CRows = mutableListOf<EcoUrp2CRow>()

        for (iw in ecoInvoices) {
            val inv  = iw.invoice
            val role = inv.ecoRole ?: ""
            val docDate = formatDate(inv.invoiceDate)
            val pos = formatPos(inv.customerStateCode, inv.customerState ?: "")

            val byRate = iw.items.groupBy { effectiveRate(it) }
            for ((rate, items) in byRate) {
                val taxable = items.sumOf { it.taxableAmount }
                val cess    = items.sumOf { it.cessAmount }

                when {
                    role.contains("B2B", ignoreCase = true) && !inv.customerGst.isNullOrBlank() -> {
                        ecoB2BRows.add(EcoB2BRow(
                            supplierGstin  = inv.ecoSupplierGstin ?: "",
                            supplierName   = inv.ecoSupplierName ?: "",
                            recipientGstin = inv.customerGst,
                            recipientName  = inv.businessName ?: inv.customerName ?: "",
                            docNumber      = inv.invoiceNumber,
                            docDate        = docDate,
                            supplyValue    = inv.grandTotal,
                            placeOfSupply  = pos,
                            docType        = inv.ecoDocumentType ?: "Invoice",
                            rate           = rate,
                            taxableValue   = taxable,
                            cessAmount     = cess
                        ))
                    }
                    role.contains("B2C", ignoreCase = true) && !inv.customerGst.isNullOrBlank() -> {
                        ecoB2CRows.add(EcoB2CRow(
                            supplierGstin = inv.ecoSupplierGstin ?: "",
                            supplierName  = inv.ecoSupplierName ?: "",
                            placeOfSupply = pos,
                            rate          = rate,
                            taxableValue  = taxable,
                            cessAmount    = cess
                        ))
                    }
                    role.contains("URP", ignoreCase = true) && !inv.ecoRecipientGstin.isNullOrBlank() -> {
                        ecoUrp2BRows.add(EcoUrp2BRow(
                            recipientGstin = inv.ecoRecipientGstin,
                            recipientName  = inv.ecoRecipientName ?: "",
                            docNumber      = inv.invoiceNumber,
                            docDate        = docDate,
                            supplyValue    = inv.grandTotal,
                            placeOfSupply  = pos,
                            docType        = inv.ecoDocumentType ?: "Invoice",
                            rate           = rate,
                            taxableValue   = taxable,
                            cessAmount     = cess
                        ))
                    }
                    role.contains("URP", ignoreCase = true) -> {
                        ecoUrp2CRows.add(EcoUrp2CRow(
                            placeOfSupply = pos,
                            rate          = rate,
                            taxableValue  = taxable,
                            cessAmount    = cess
                        ))
                    }
                }
            }
        }

        return Gstr1Report(
            gstin         = gstin,
            financialYear = financialYear,
            period        = period,
            returnType    = returnType,
            b2b           = b2bRows,
            b2cl          = b2clRows,
            b2cs          = b2csRows,
            cdnr          = cdnrRows,
            cdnur         = cdnurRows,
            hsnB2B        = hsnB2BRows,
            hsnB2C        = hsnB2CRows,
            docs          = docsRows,
            eco           = ecoRows,
            ecoB2B        = ecoB2BRows,
            ecoB2C        = ecoB2CRows,
            ecoUrp2B      = ecoUrp2BRows,
            ecoUrp2C      = ecoUrp2CRows
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the total GST rate for an item (CGST+SGST or IGST). */
    private fun effectiveRate(item: GstSalesInvoiceItem): Double {
        val cgstSgst = item.salesCgstPercentage + item.salesSgstPercentage
        return if (item.salesIgstPercentage > 0.0) item.salesIgstPercentage else cgstSgst
    }

    /** Formats epoch-millis to "dd-MMM-yy" required by GST Offline Tool. */
    private fun formatDate(millis: Long): String =
        if (millis > 0L) dateFmt.format(Date(millis)).uppercase() else ""

    /** Returns state code extracted from place-of-supply string or customerStateCode. */
    private fun extractStateCode(pos: String): String =
        pos.substringBefore("-").trim().take(2)

    /**
     * Returns "NN-State Name" format expected by the GST Offline Tool.
     * Falls back to the stored placeOfSupply string if it already contains "-".
     */
    private fun formatPos(stateCode: String?, placeOfSupply: String): String {
        if (!stateCode.isNullOrBlank() && !placeOfSupply.contains("-")) {
            return "$stateCode-$placeOfSupply"
        }
        return placeOfSupply
    }

    /** Maps internal gstrInvoiceType to the CSV value expected by the portal. */
    private fun mapGstrInvoiceType(type: String): String = when (type) {
        "SEZ supplies with payment"    -> "SEZ supplies with payment"
        "SEZ supplies without payment" -> "SEZ supplies without payment"
        "Deemed Exp"                   -> "Deemed Exp"
        else                           -> "Regular"
    }
}
