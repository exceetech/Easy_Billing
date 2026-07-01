package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.gstr1.Gstr1DraftEntity
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Gstr1DraftsAdapter(
    private val drafts: List<Gstr1DraftEntity>,
    private val onOpen: (Gstr1DraftEntity) -> Unit,
    private val onDelete: (Gstr1DraftEntity) -> Unit
) : RecyclerView.Adapter<Gstr1DraftsAdapter.VH>() {

    private val dateFmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView   = view.findViewById(R.id.tvDraftTitle)
        val tvSub: TextView     = view.findViewById(R.id.tvDraftSubtitle)
        val btnOpen: MaterialButton   = view.findViewById(R.id.btnOpen)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDeleteDraft)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_gstr1_draft, parent, false))

    override fun getItemCount() = drafts.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val draft = drafts[position]
        holder.tvTitle.text = "${draft.period} ${draft.financialYear}  (${draft.returnType})"
        holder.tvSub.text   = "Saved: ${dateFmt.format(Date(draft.updatedAt))}"
        holder.btnOpen.setOnClickListener { onOpen(draft) }
        holder.btnDelete.setOnClickListener { onDelete(draft) }
    }
}
