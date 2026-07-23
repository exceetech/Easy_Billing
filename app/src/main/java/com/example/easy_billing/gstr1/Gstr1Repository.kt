package com.example.easy_billing.gstr1

import com.example.easy_billing.util.appNow

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.CreditNote
import com.example.easy_billing.db.CreditNoteItem
import com.example.easy_billing.db.GstProfile
import com.example.easy_billing.db.GstSalesInvoice
import com.example.easy_billing.db.GstSalesInvoiceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Gstr1Repository
 *
 * Fetches raw invoice / credit-note data for a given filing period
 * from Room tables:
 *   • gst_sales_invoice_table  (header)
 *   • gst_sales_items_table    (line items)
 *   • credit_notes             (credit / debit note headers)
 *   • credit_note_items        (note line items)
 *
 * Does NOT classify, aggregate, or filter by B2B/B2C — that is
 * [Gstr1Generator]'s job.
 */
class Gstr1Repository(private val context: Context) {

    private val db by lazy { AppDatabase.getDatabase(context) }

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
        val creditNotes: List<CreditNoteWithItems>
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Fetch
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
                creditNotes = notesWithItems
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
