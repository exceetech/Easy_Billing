package com.example.easy_billing.gstr2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.gstr1.Gstr1RowAdapter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class Gstr2SectionFragment : Fragment() {

    private val viewModel: com.example.easy_billing.viewmodel.Gstr2ViewModel by activityViewModels()

    companion object {
        private const val ARG_POSITION = "position"

        fun newInstance(position: Int, json: String? = null): Gstr2SectionFragment {
            return Gstr2SectionFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_POSITION, position)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_gstr1_section, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvRows = view.findViewById<RecyclerView>(R.id.rvRows)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)
        val tvRowCount = view.findViewById<TextView>(R.id.tvRowCount)

        val position = arguments?.getInt(ARG_POSITION) ?: 0

        rvRows.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.report.collectLatest { report ->
                val rows = buildRowsFromReport(position, report)

                if (rows.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    rvRows.visibility = View.GONE
                    tvRowCount.text = "0 rows"
                } else {
                    tvEmpty.visibility = View.GONE
                    rvRows.visibility = View.VISIBLE
                    tvRowCount.text = "${rows.size} row(s)"
                    rvRows.adapter = Gstr1RowAdapter(rows)
                }
            }
        }
    }

    private fun buildRowsFromReport(position: Int, report: Gstr2Report?): List<Pair<String, String>> {
        if (report == null) return emptyList()
        return try {
            when (position) {
                0 -> report.b2b.map { Pair("${it.invoiceNumber} · ${it.invoiceDate}", "GSTIN: ${it.supplierGstin} | Taxable: ₹${it.taxableValue}") }
                1 -> report.b2bur.map { Pair("${it.invoiceNumber} · ${it.invoiceDate}", "Supplier: ${it.supplierName} | Taxable: ₹${it.taxableValue}") }
                2 -> report.imps.map { Pair("${it.invoiceNumber} · ${it.invoiceDate}", "POS: ${it.placeOfSupply} | Taxable: ₹${it.taxableValue}") }
                3 -> report.impg.map { Pair("${it.billOfEntryNumber} · ${it.billOfEntryDate}", "Port: ${it.portCode} | Taxable: ₹${it.taxableValue}") }
                4 -> report.cdnr.map { Pair("${it.noteNumber} · ${it.noteDate}", "GSTIN: ${it.supplierGstin} | Taxable: ₹${it.taxableValue}") }
                5 -> report.cdnur.map { Pair("${it.noteNumber} · ${it.noteDate}", "Type: ${it.documentType} | Taxable: ₹${it.taxableValue}") }
                6 -> report.exemp.map { Pair(it.description, "Nil Rated: ₹${it.nilRated} | Exempt: ₹${it.exempted}") }
                7 -> report.hsnsum.map { Pair("HSN: ${it.hsn}", "Total Value: ₹${it.totalValue} | Taxable: ₹${it.taxableValue}") }
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildRows(position: Int, json: String): List<Pair<String, String>> {
        val gson = Gson()
        return try {
            when (position) {
                0 -> {
                    val list = gson.fromJson<List<Gstr2B2bRow>>(json, object : TypeToken<List<Gstr2B2bRow>>() {}.type)
                    list.map { Pair("${it.invoiceNumber} · ${it.invoiceDate}", "GSTIN: ${it.supplierGstin} | Taxable: ₹${it.taxableValue}") }
                }
                1 -> {
                    val list = gson.fromJson<List<Gstr2B2burRow>>(json, object : TypeToken<List<Gstr2B2burRow>>() {}.type)
                    list.map { Pair("${it.invoiceNumber} · ${it.invoiceDate}", "Supplier: ${it.supplierName} | Taxable: ₹${it.taxableValue}") }
                }
                2 -> {
                    val list = gson.fromJson<List<Gstr2ImpsRow>>(json, object : TypeToken<List<Gstr2ImpsRow>>() {}.type)
                    list.map { Pair("${it.invoiceNumber} · ${it.invoiceDate}", "POS: ${it.placeOfSupply} | Taxable: ₹${it.taxableValue}") }
                }
                3 -> {
                    val list = gson.fromJson<List<Gstr2ImpgRow>>(json, object : TypeToken<List<Gstr2ImpgRow>>() {}.type)
                    list.map { Pair("${it.billOfEntryNumber} · ${it.billOfEntryDate}", "Port: ${it.portCode} | Taxable: ₹${it.taxableValue}") }
                }
                4 -> {
                    val list = gson.fromJson<List<Gstr2CdnrRow>>(json, object : TypeToken<List<Gstr2CdnrRow>>() {}.type)
                    list.map { Pair("${it.noteNumber} · ${it.noteDate}", "GSTIN: ${it.supplierGstin} | Taxable: ₹${it.taxableValue}") }
                }
                5 -> {
                    val list = gson.fromJson<List<Gstr2CdnurRow>>(json, object : TypeToken<List<Gstr2CdnurRow>>() {}.type)
                    list.map { Pair("${it.noteNumber} · ${it.noteDate}", "Type: ${it.documentType} | Taxable: ₹${it.taxableValue}") }
                }
                6 -> {
                    val list = gson.fromJson<List<Gstr2ExempRow>>(json, object : TypeToken<List<Gstr2ExempRow>>() {}.type)
                    list.map { Pair(it.description, "Nil Rated: ₹${it.nilRated} | Exempt: ₹${it.exempted}") }
                }
                7 -> {
                    val list = gson.fromJson<List<Gstr2HsnsumRow>>(json, object : TypeToken<List<Gstr2HsnsumRow>>() {}.type)
                    list.map { Pair("HSN: ${it.hsn}", "Total Value: ₹${it.totalValue} | Taxable: ₹${it.taxableValue}") }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
