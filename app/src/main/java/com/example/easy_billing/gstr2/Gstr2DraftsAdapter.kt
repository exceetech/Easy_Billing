package com.example.easy_billing.gstr2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Gstr2DraftsAdapter(
    private val drafts: List<Gstr2DraftEntity>,
    private val onOpen: (Gstr2DraftEntity) -> Unit,
    private val onDelete: (Gstr2DraftEntity) -> Unit
) : RecyclerView.Adapter<Gstr2DraftsAdapter.ViewHolder>() {

    private val df = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvDraftTitle)
        val tvSubtitle: TextView = view.findViewById(R.id.tvDraftSubtitle)
        val btnOpen: MaterialButton = view.findViewById(R.id.btnOpen)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDeleteDraft)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gstr1_draft, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val d = drafts[position]
        holder.tvTitle.text = "${d.period} ${d.financialYear}"
        holder.tvSubtitle.text = "Saved: ${df.format(Date(d.updatedAt))} • ${d.returnType}"
        holder.btnOpen.setOnClickListener { onOpen(d) }
        holder.btnDelete.setOnClickListener { onDelete(d) }
    }

    override fun getItemCount(): Int = drafts.size
}
