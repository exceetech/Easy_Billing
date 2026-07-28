package com.example.easy_billing

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.CreditNote
import com.example.easy_billing.util.CurrencyHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rows for the "Issued notes" screen (activity_credit_debit_notes.xml)
 * — same card shell and hash-based random row palette as
 * BillHistoryAdapter / BatchPickerAdapter, but colors the amount by
 * note type instead of by row: credit notes (money owed back to the
 * customer) show a red minus, debit notes (additional amount owed)
 * show a teal plus.
 */
class CreditDebitNoteAdapter(
    private val onNoteClick: (CreditNote) -> Unit
) : RecyclerView.Adapter<CreditDebitNoteAdapter.NoteVH>() {

    private var notes: List<CreditNote> = emptyList()

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    private data class RowColor(val stripe: Int, val avatarBg: Int, val avatarText: Int)

    private val rowPalette = listOf(
        RowColor(Color.parseColor("#1D6E6E"), Color.parseColor("#DDEEEE"), Color.parseColor("#1D6E6E")),
        RowColor(Color.parseColor("#B23A3A"), Color.parseColor("#FBEDED"), Color.parseColor("#B23A3A")),
        RowColor(Color.parseColor("#8A6526"), Color.parseColor("#FAEEDA"), Color.parseColor("#8A6526")),
        RowColor(Color.parseColor("#3A5FB2"), Color.parseColor("#E5EBFA"), Color.parseColor("#3A5FB2")),
        RowColor(Color.parseColor("#7A4FA3"), Color.parseColor("#EFE5F7"), Color.parseColor("#7A4FA3")),
        RowColor(Color.parseColor("#B2673A"), Color.parseColor("#FAEBE1"), Color.parseColor("#B2673A")),
        RowColor(Color.parseColor("#3A8F6E"), Color.parseColor("#E1F2EA"), Color.parseColor("#3A8F6E")),
        RowColor(Color.parseColor("#B23A85"), Color.parseColor("#FAE1F0"), Color.parseColor("#B23A85"))
    )

    private fun colorFor(note: CreditNote): RowColor {
        val idx = kotlin.math.abs(note.id.hashCode()) % rowPalette.size
        return rowPalette[idx]
    }

    inner class NoteVH(view: View) : RecyclerView.ViewHolder(view) {
        val stripe: View = view.findViewById(R.id.viewItemStripe)
        val avatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvNumber: TextView = view.findViewById(R.id.tvNoteNumber)
        val tvMeta: TextView = view.findViewById(R.id.tvNoteMeta)
        val tvDate: TextView = view.findViewById(R.id.tvNoteDate)
        val tvAmount: TextView = view.findViewById(R.id.tvNoteAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_credit_debit_note, parent, false)
        return NoteVH(view)
    }

    override fun onBindViewHolder(holder: NoteVH, position: Int) {
        val note = notes[position]
        val rowColor = colorFor(note)
        val isCredit = note.noteType == "C"

        holder.stripe.setBackgroundColor(rowColor.stripe)
        holder.avatar.setTextColor(rowColor.avatarText)
        holder.avatar.background.setTint(rowColor.avatarBg)
        holder.avatar.text = if (isCredit) "CN" else "DN"

        holder.tvNumber.text = note.noteNumber
        holder.tvMeta.text = buildString {
            if (note.customerName.isNotBlank()) {
                append(note.customerName)
                append(" · ")
            }
            append("against ")
            append(note.originalInvoiceNumber)
        }
        holder.tvDate.text = dateFmt.format(Date(note.noteDate))

        val amountText = CurrencyHelper.format(holder.itemView.context, note.totalAmount)
        holder.tvAmount.text = if (isCredit) "−$amountText" else "+$amountText"
        holder.tvAmount.setTextColor(
            Color.parseColor(if (isCredit) "#791F1F" else "#0F6E56")
        )

        holder.itemView.setOnClickListener { onNoteClick(note) }
    }

    override fun getItemCount(): Int = notes.size

    fun submitList(list: List<CreditNote>) {
        notes = list
        notifyDataSetChanged()
    }
}
