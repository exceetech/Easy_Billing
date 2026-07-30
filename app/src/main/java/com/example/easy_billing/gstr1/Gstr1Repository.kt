package com.example.easy_billing.gstr1

import com.example.easy_billing.util.appNow

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.CreditNote
import com.example.easy_billing.db.CreditNoteItem
import com.example.easy_billing.db.GstProfile
import com.example.easy_billing.db.GstSalesInvoice
import com.example.easy_billing.db.GstSalesInvoiceItem
import com.example.easy_billing.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Gstr1Repository
 *
 * Phase 6 (GSTR-1 online-parity plan): GSTR-1 used to compute entirely
 * on-device from Room tables. It now calls the backend's
 * `/gst/reports/gstr1` endpoint instead — the same architecture GSTR-2
 * already used — since the backend endpoint was brought up to full
 * section parity (B2B/B2CL/B2CS/CDNR/CDNUR/HSN split/DOCS) in Phase 1.
 * The on-device Room-based path ([fetchForPeriod] + [Gstr1Generator]) is
 * kept in the codebase but is no longer called by [Gstr1ViewModel] — see
 * the class doc on [fetchForPeriod] for why it wasn't deleted outright.
 *
 * [periodRange] / [Gstr1Generator] still exist and remain correct
 * (leap-year-safe), they're just not on the primary path anymore.
 */
class Gstr1Repository(private val context: Context) {

    private val db by lazy { AppDatabase.getDatabase(context) }
    private val api: ApiService by lazy { com.example.easy_billing.network.RetrofitClient.api }

    // ─────────────────────────────────────────────────────────────────────────
    //  Period helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns epoch-millis [start, end) for the given filing period.
     *
     * @param financialYear "2025-26"
     * @param period        For Monthly: "April", "May", … "March".
     *                      For Quarterly: "Apr-Jun", "Jul-Sep", "Oct-Dec", "Jan-Mar".
     */
    fun periodRange(financialYear: String, period: String): Pair<Long, Long> {
        val startYear = financialYear.substringBefore("-").toInt()
        val months: List<Int> = monthsForPeriod(period, startYear)

        val start = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, months.first().let { if (it >= 4) startYear else startYear + 1 })
            set(Calendar.MONTH, months.first() - 1)       // Calendar.MONTH is 0-based
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val lastMonth = months.last()
        val lastYear  = if (lastMonth >= 4) startYear else startYear + 1
        val end = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, lastYear)
            set(Calendar.MONTH, lastMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        return start to end
    }

    /** Returns 1-based month numbers for the given period label. */
    private fun monthsForPeriod(period: String, startYear: Int): List<Int> = when (period) {
        "April"    -> listOf(4)
        "May"      -> listOf(5)
        "June"     -> listOf(6)
        "July"     -> listOf(7)
        "August"   -> listOf(8)
        "September"-> listOf(9)
        "October"  -> listOf(10)
        "November" -> listOf(11)
        "December" -> listOf(12)
        "January"  -> listOf(1)
        "February" -> listOf(2)
        "March"    -> listOf(3)
        "Apr-Jun"  -> listOf(4, 5, 6)
        "Jul-Sep"  -> listOf(7, 8, 9)
        "Oct-Dec"  -> listOf(10, 11, 12)
        "Jan-Mar"  -> listOf(1, 2, 3)
        else       -> listOf(4)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Data classes for raw fetch result
    // ─────────────────────────────────────────────────────────────────────────

    data class InvoiceWithItems(
        val invoice: GstSalesInvoice,
        val items: List<GstSalesInvoiceItem>
    )

    data class CreditNoteWithItems(
        val note: CreditNote,
        val items: List<CreditNoteItem>
    )

    data class RawGstr1Data(
        val profile: GstProfile?,
        val invoices: List<InvoiceWithItems>,
        val creditNotes: List<CreditNoteWithItems>,
        /**
         * Every invoice the shop has, keyed by invoice number — NOT just this
         * period's. Needed because whether a credit note belongs in CDNUR
         * depends on the ORIGINAL sale, which is very often from an earlier
         * return period. [invoices] alone can't answer that.
         */
        val allInvoicesByNumber: Map<String, GstSalesInvoice> = emptyMap()
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Online fetch (Phase 6 — primary path)
    // ─────────────────────────────────────────────────────────────────────────

    private val apiDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * Phase 7: true if there are bills or credit/debit notes not yet
     * pushed to the server for this shop. Called before hitting the
     * network so the user gets a clear warning instead of a report that's
     * quietly missing whatever's still sitting on the phone.
     */
    suspend fun hasPendingSync(): Boolean = withContext(Dispatchers.IO) {
        db.gstSalesInvoiceDao().getUnsynced().isNotEmpty() ||
            db.creditNoteDao().getUnsynced().isNotEmpty() ||
            // A bill cancelled on this device but not yet pushed still shows as
            // live on the server, so the report would OVER-report a voided sale.
            // getCancelledBills() returns exactly is_cancelled=1 AND
            // cancel_synced=0 rows.
            db.billDao().getCancelledBills().isNotEmpty()
    }

    /**
     * Fetches GSTR-1 from the backend for the given filing period and maps
     * the response DTO onto the same [Gstr1Report] shape the UI, validator,
     * and CSV/Excel exporters already understand — so nothing downstream
     * of this call needed to change for the online switch.
     *
     * ECO / ECO-B2B / ECO-B2C / ECO-URP2B / ECO-URP2C are now mapped too
     * (GST-reports fix round 2, Phase 2) — the backend endpoint computes
     * them the same way Gstr1Generator.kt used to on-device. When they're
     * genuinely empty (no e-commerce-operator sales that period),
     * Gstr1SectionFragment's normal "no records" empty state applies —
     * NOT the "not available yet" warning from Phase 1 of the same round,
     * since the data is real now.
     */
    suspend fun fetchGstr1Online(
        token: String,
        financialYear: String,
        period: String,
        returnType: String
    ): Gstr1Report = withContext(Dispatchers.IO) {
        val (startMs, endMs) = periodRange(financialYear, period)
        val startDate = apiDateFmt.format(Date(startMs))
        val endDate = apiDateFmt.format(Date(endMs))

        val response = api.getGstr1(token, startDate, endDate)
        val profile = db.gstProfileDao().get()

        Gstr1Report(
            gstin = profile?.gstin ?: "",
            financialYear = financialYear,
            period = period,
            returnType = returnType,
            b2b = response.b2b.map {
                // Previously receiverName / reverseCharge / invoiceType / ecomGstin /
                // cessAmount were hardcoded here ("", "N", "Regular", "", 0.0), which
                // filed reverse-charge supplies as ordinary Table 4A sales, reported
                // SEZ / deemed-export invoices as Regular, and dropped cess. The
                // server now sends all five, so use them.
                B2BRow(
                    gstin = it.customer_gstin, receiverName = it.receiver_name,
                    invoiceNumber = it.invoice_number,
                    invoiceDate = it.invoice_date, invoiceValue = it.invoice_value,
                    placeOfSupply = it.place_of_supply, reverseCharge = it.reverse_charge,
                    applicableRate = "",
                    invoiceType = it.invoice_type, ecomGstin = it.ecom_gstin, rate = it.gst_rate,
                    taxableValue = it.taxable_value, cessAmount = it.cess_amount
                )
            },
            b2cl = response.b2cl.map {
                B2CLRow(
                    invoiceNumber = it.invoice_number, invoiceDate = it.invoice_date,
                    invoiceValue = it.invoice_value, placeOfSupply = it.place_of_supply,
                    applicableRate = "", rate = it.rate, taxableValue = it.taxable_value,
                    cessAmount = it.cess_amount, ecomGstin = it.ecom_gstin
                )
            },
            b2cs = response.b2cs.map {
                B2CSRow(
                    type = it.type, placeOfSupply = it.place_of_supply, rate = it.rate,
                    applicableRate = "", taxableValue = it.taxable_value,
                    cessAmount = it.cess_amount, ecomGstin = it.ecom_gstin
                )
            },
            cdnr = response.cdnr.map {
                CdnrRow(
                    gstin = it.customer_gstin, receiverName = it.receiver_name,
                    noteNumber = it.note_number, noteDate = it.note_date, noteType = it.note_type,
                    placeOfSupply = it.place_of_supply, reverseCharge = it.reverse_charge,
                    noteSupplyType = it.note_supply_type, noteValue = it.note_value,
                    applicableRate = "", rate = it.rate, taxableValue = it.taxable_value,
                    cessAmount = it.cess_amount
                )
            },
            cdnur = response.cdnur.map {
                CdnurRow(
                    urType = it.ur_type, noteNumber = it.note_number, noteDate = it.note_date,
                    noteType = it.note_type, placeOfSupply = it.place_of_supply,
                    noteValue = it.note_value, applicableRate = "", rate = it.rate,
                    taxableValue = it.taxable_value, cessAmount = it.cess_amount
                )
            },
            hsnB2B = response.hsn_b2b.map {
                // totalValue / cessAmount / rate used to be faked here
                // (taxable+tax, 0.0, 0.0) because the server didn't send them.
                // It does now, so file the real figures.
                HsnRow(
                    hsn = it.hsn_code, description = it.description, uqc = it.uom,
                    totalQuantity = it.total_quantity,
                    totalValue = if (it.total_value != 0.0) it.total_value
                                 else it.taxable_value + it.total_tax + it.cess_amount,
                    taxableValue = it.taxable_value, igstAmount = it.igst_amount,
                    cgstAmount = it.cgst_amount, sgstAmount = it.sgst_amount,
                    cessAmount = it.cess_amount,
                    rate = it.rate
                )
            },
            hsnB2C = response.hsn_b2c.map {
                // totalValue / cessAmount / rate used to be faked here
                // (taxable+tax, 0.0, 0.0) because the server didn't send them.
                // It does now, so file the real figures.
                HsnRow(
                    hsn = it.hsn_code, description = it.description, uqc = it.uom,
                    totalQuantity = it.total_quantity,
                    totalValue = if (it.total_value != 0.0) it.total_value
                                 else it.taxable_value + it.total_tax + it.cess_amount,
                    taxableValue = it.taxable_value, igstAmount = it.igst_amount,
                    cgstAmount = it.cgst_amount, sgstAmount = it.sgst_amount,
                    cessAmount = it.cess_amount,
                    rate = it.rate
                )
            },
            docs = response.docs.map {
                DocsRow(
                    natureOfDoc = it.nature_of_document, srFrom = it.sr_from, srTo = it.sr_to,
                    totalNumber = it.total_number, cancelled = it.cancelled
                )
            },
            eco = response.eco.map {
                EcoRow(
                    natureOfSupply = it.nature_of_supply, ecoGstin = it.eco_gstin, ecoName = it.eco_name,
                    netValue = it.net_value, igst = it.igst, cgst = it.cgst, sgst = it.sgst, cess = it.cess
                )
            },
            ecoB2B = response.eco_b2b.map {
                EcoB2BRow(
                    supplierGstin = it.supplier_gstin, supplierName = it.supplier_name,
                    recipientGstin = it.recipient_gstin, recipientName = it.recipient_name,
                    docNumber = it.doc_number, docDate = it.doc_date, supplyValue = it.supply_value,
                    placeOfSupply = it.place_of_supply, docType = it.doc_type,
                    rate = it.rate, taxableValue = it.taxable_value, cessAmount = it.cess_amount
                )
            },
            ecoB2C = response.eco_b2c.map {
                EcoB2CRow(
                    supplierGstin = it.supplier_gstin, supplierName = it.supplier_name,
                    placeOfSupply = it.place_of_supply, rate = it.rate,
                    taxableValue = it.taxable_value, cessAmount = it.cess_amount
                )
            },
            ecoUrp2B = response.eco_urp2b.map {
                EcoUrp2BRow(
                    recipientGstin = it.recipient_gstin, recipientName = it.recipient_name,
                    docNumber = it.doc_number, docDate = it.doc_date, supplyValue = it.supply_value,
                    placeOfSupply = it.place_of_supply, docType = it.doc_type,
                    rate = it.rate, taxableValue = it.taxable_value, cessAmount = it.cess_amount
                )
            },
            ecoUrp2C = response.eco_urp2c.map {
                EcoUrp2CRow(
                    placeOfSupply = it.place_of_supply, rate = it.rate,
                    taxableValue = it.taxable_value, cessAmount = it.cess_amount
                )
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  On-device fetch (pre-Phase-6 path — kept, not deleted)
    //
    //  Not called by Gstr1ViewModel anymore. Left in place rather than
    //  removed because: (a) Gstr1Generator's classification logic is what
    //  Phase 1's backend port was translated FROM and is still the
    //  reference implementation if the two ever need to be compared again,
    //  and (b) it's a ready-made offline fallback if a future decision
    //  reverses Phase 7's online-only choice. Not wired to any UI today.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun fetchForPeriod(financialYear: String, period: String): RawGstr1Data =
        withContext(Dispatchers.IO) {

            val (start, end) = periodRange(financialYear, period)

            val profile = db.gstProfileDao().get()

            // Invoices in the period window (use invoice_date, fall back to created_at)
            val allInvoices = db.gstSalesInvoiceDao().getAll()
            val periodInvoices = allInvoices.filter { inv ->
                val ts = if (inv.invoiceDate > 0L) inv.invoiceDate else inv.createdAt
                ts in start..end
            }

            val invoicesWithItems = periodInvoices.map { inv ->
                val items = db.gstSalesInvoiceItemDao().getByInvoice(inv.id)
                InvoiceWithItems(inv, items)
            }

            // Credit / debit notes in the period window (use noteDate).
            // Deep-dive fix, Issue 5: a note issued against a bill that was
            // LATER cancelled used to keep appearing here indefinitely,
            // referencing an invoice number that's no longer valid once the
            // original bill is void. Excluded the same way the backend
            // profit/GST-email queries already exclude them (Issues 1/3/4).
            val cancelledBillIds = db.billDao().getCancelledBillIds().toSet()
            val allNotes = db.creditNoteDao().getAll()
            val periodNotes = allNotes.filter { note ->
                note.noteDate in start..end && note.originalInvoiceId !in cancelledBillIds
            }

            val notesWithItems = periodNotes.map { note ->
                val items = db.creditNoteItemDao().getByNote(note.id)
                CreditNoteWithItems(note, items)
            }

            RawGstr1Data(
                profile     = profile,
                invoices    = invoicesWithItems,
                creditNotes = notesWithItems,
                // Built from allInvoices, not periodInvoices: a credit note's
                // original sale is usually from an earlier period, and CDNUR
                // classification depends on that original. Costs nothing extra
                // — allInvoices is already in memory above.
                allInvoicesByNumber = allInvoices
                    .filter { it.invoiceNumber.isNotBlank() }
                    .associateBy { it.invoiceNumber.trim() }
            )
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  Draft persistence
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun saveDraft(report: Gstr1Report): Long = withContext(Dispatchers.IO) {
        val existing = db.gstr1DraftDao().find(report.gstin, report.financialYear, report.period)
        val entity = Gstr1DraftEntity(
            id            = existing?.id ?: 0,
            gstin         = report.gstin,
            financialYear = report.financialYear,
            period        = report.period,
            returnType    = report.returnType,
            reportJson    = report.toJson(),
            generatedAt   = existing?.generatedAt ?: appNow(),
            updatedAt     = appNow()
        )
        db.gstr1DraftDao().upsert(entity)
    }

    suspend fun getDrafts(): List<Gstr1DraftEntity> = withContext(Dispatchers.IO) {
        db.gstr1DraftDao().getAll()
    }

    suspend fun getDraftById(id: Int): Gstr1Report? = withContext(Dispatchers.IO) {
        db.gstr1DraftDao().getById(id)?.let { Gstr1Report.fromJson(it.reportJson) }
    }

    suspend fun deleteDraft(id: Int) = withContext(Dispatchers.IO) {
        db.gstr1DraftDao().deleteById(id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GST Profile helpers
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getProfile(): GstProfile? = withContext(Dispatchers.IO) {
        db.gstProfileDao().get()
    }
}
