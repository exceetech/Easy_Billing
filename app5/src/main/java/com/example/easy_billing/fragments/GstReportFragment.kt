package com.example.easy_billing.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.adapter.GstReportItem
import com.example.easy_billing.adapter.GstReportsAdapter

class GstReportFragment : Fragment() {

    private lateinit var rvRecords: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: GstReportsAdapter

    companion object {
        fun newInstance() = GstReportFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_gst_report, container, false)
        rvRecords = view.findViewById(R.id.rvRecords)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        
        rvRecords.layoutManager = LinearLayoutManager(context)
        adapter = GstReportsAdapter(emptyList())
        rvRecords.adapter = adapter
        
        return view
    }

    fun updateData(items: List<GstReportItem>) {
        if (!::adapter.isInitialized) return
        
        adapter.updateData(items)
        if (items.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvRecords.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvRecords.visibility = View.VISIBLE
        }
    }
}
