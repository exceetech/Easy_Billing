package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.CreditNote
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Issued notes" — lists every Credit Note and Debit Note ever issued,
 * behind a Credit/Debit segmented tab. Both live in the same
 * credit_notes table (CreditNote.noteType == "C" or "D"), so this is
 * just a filtered view over CreditNoteDao.getAll() — no new schema.
 *
 * Reached from Bill History via the "Notes" pill next to the header.
 */
class CreditDebitNotesActivity : BaseActivity() {

    companion object {
        /** Optional — scopes the list to notes issued against one bill. */
        const val EXTRA_BILL_ID = "extra_bill_id"
        const val EXTRA_BILL_NUMBER = "extra_bill_number"
    }

    private lateinit var db: AppDatabase
    private lateinit var rvNotes: RecyclerView
    private lateinit var progressNotes: ProgressBar
    private lateinit var tvNotesEmpty: TextView
    private lateinit var tvNotesSubtitle: TextView
    private lateinit var tabCredit: View
    private lateinit var tabDebit: View
    private lateinit var tvTabCreditLabel: TextView
    private lateinit var tvTabDebitLabel: TextView

    private lateinit var adapter: CreditDebitNoteAdapter

    private var allNotes: List<CreditNote> = emptyList()
    private var activeType: String = "C"
    private var scopedBillId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credit_debit_notes)
        com.example.easy_billing.util.UserEventLogger.logAction("CreditDebitNotes", "opened")
        db = AppDatabase.getDatabase(this)

        scopedBillId = intent.getIntExtra(EXTRA_BILL_ID, -1)
        val scopedBillNumber = intent.getStringExtra(EXTRA_BILL_NUMBER)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_back_arrow)
            setNavigationOnClickListener { finish() }
        }

        rvNotes = findViewById(R.id.rvNotes)
        progressNotes = findViewById(R.id.progressNotes)
        tvNotesEmpty = findViewById(R.id.tvNotesEmpty)
        tvNotesSubtitle = findViewById(R.id.tvNotesSubtitle)
        tabCredit = findViewById(R.id.tabCredit)
        tabDebit = findViewById(R.id.tabDebit)
        tvTabCreditLabel = findViewById(R.id.tvTabCreditLabel)
        tvTabDebitLabel = findViewById(R.id.tvTabDebitLabel)

        if (scopedBillId != -1 && !scopedBillNumber.isNullOrBlank()) {
            tvNotesSubtitle.text = "Against $scopedBillNumber"
            tvNotesSubtitle.visibility = View.VISIBLE
        }

        adapter = CreditDebitNoteAdapter { note ->
            // Detail view isn't built yet — a quick toast confirms the
            // tap lands on the right row until a note-detail screen exists.
            android.widget.Toast.makeText(
                this,
                "${note.noteNumber} · against ${note.originalInvoiceNumber}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        rvNotes.layoutManager = LinearLayoutManager(this)
        rvNotes.adapter = adapter

        tabCredit.setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("CreditDebitNotes", "tab_credit_clicked")
            selectTab("C")
        }
        tabDebit.setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("CreditDebitNotes", "tab_debit_clicked")
            selectTab("D")
        }

        loadNotes()
    }

    private fun selectTab(type: String) {
        activeType = type
        tabCredit.setBackgroundResource(
            if (type == "C") R.drawable.bg_credit_tile_teal else android.R.color.transparent
        )
        tabDebit.setBackgroundResource(
            if (type == "D") R.drawable.bg_credit_tile_teal else android.R.color.transparent
        )
        tvTabCreditLabel.setTextColor(
            android.graphics.Color.parseColor(if (type == "C") "#085041" else "#9A8F79")
        )
        tvTabDebitLabel.setTextColor(
            android.graphics.Color.parseColor(if (type == "D") "#085041" else "#9A8F79")
        )
        render()
    }

    private fun loadNotes() {
        progressNotes.visibility = View.VISIBLE
        tvNotesEmpty.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.IO) {
            val notes = if (scopedBillId != -1) {
                db.creditNoteDao().getByOriginalInvoice(scopedBillId)
            } else {
                db.creditNoteDao().getAll()
            }
            withContext(Dispatchers.Main) {
                allNotes = notes
                progressNotes.visibility = View.GONE
                updateTabLabels()
                render()
            }
        }
    }

    private fun updateTabLabels() {
        val creditCount = allNotes.count { it.noteType == "C" }
        val debitCount = allNotes.count { it.noteType == "D" }
        tvTabCreditLabel.text = "Credit notes · $creditCount"
        tvTabDebitLabel.text = "Debit notes · $debitCount"
    }

    private fun render() {
        val filtered = allNotes.filter { it.noteType == activeType }
        adapter.submitList(filtered)
        tvNotesEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        val scope = if (scopedBillId != -1) " for this bill" else ""
        tvNotesEmpty.text = if (activeType == "C") "No credit notes issued$scope yet" else "No debit notes issued$scope yet"
    }
}
