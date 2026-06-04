package com.example.easy_billing.gstr1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R

/**
 * Fragment displayed inside each GSTR-1 section tab.
 *
 * Receives the current [Gstr1Report] via arguments (position-indexed)
 * and renders the matching section's rows in a RecyclerView.
 *
 * two-line card using the generic [item_gstr1_row.xml] layout.
 */
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class Gstr1SectionFragment : Fragment() {

    private val viewModel: com.example.easy_billing.viewmodel.Gstr1ViewModel by activityViewModels()

    companion object {
        private const val ARG_POSITION = "position"

        fun newInstance(position: Int, report: Gstr1Report? = null): Gstr1SectionFragment {
            return Gstr1SectionFragment().apply {
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

        val rvRows    = view.findViewById<RecyclerView>(R.id.rvRows)
        val tvEmpty   = view.findViewById<TextView>(R.id.tvEmpty)
        val tvRowCount = view.findViewById<TextView>(R.id.tvRowCount)

        val position = arguments?.getInt(ARG_POSITION) ?: 0

        rvRows.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.report.collectLatest { report ->
                val rows: List<Pair<String, String>> = report?.let { buildRows(it, position) } ?: emptyList()

                if (rows.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    rvRows.visibility  = View.GONE
                    tvRowCount.text    = "0 rows"
                } else {
                    tvEmpty.visibility = View.GONE
                    rvRows.visibility  = View.VISIBLE
                    tvRowCount.text    = "${rows.size} row(s)"
                    rvRows.adapter = Gstr1RowAdapter(rows)
                }
            }
        }
    }

    /**
     * Returns list of (primary, secondary) string pairs for display.
     * Position maps to [Gstr1SheetTabAdapter.TAB_LABELS].
     */
    private fun buildRows(report: Gstr1Report, position: Int): List<Pair<String, String>> =
        when (position) {
            0 -> report.b2b.map {
                Pair(
                    "${it.invoiceNumber}  ·  ${it.invoiceDate}",
                    "GSTIN: ${it.gstin}  |  Rate: ${it.rate}%  |  Taxable: ₹${"%.2f".format(it.taxableValue)}"
                )
            }
            1 -> report.b2cl.map {
                Pair(
                    "${it.invoiceNumber}  ·  ${it.invoiceDate}",
                    "POS: ${it.placeOfSupply}  |  Rate: ${it.rate}%  |  Taxable: ₹${"%.2f".format(it.taxableValue)}"
                )
            }
            2 -> report.b2cs.map {
                Pair(
                    "POS: ${it.placeOfSupply}  |  Rate: ${it.rate}%",
                    "Type: ${it.type}  |  Taxable: ₹${"%.2f".format(it.taxableValue)}"
                )
            }
            3 -> report.cdnr.map {
                Pair(
                    "${it.noteNumber}  ·  ${it.noteDate}  [${it.noteType}]",
                    "GSTIN: ${it.gstin}  |  Rate: ${it.rate}%  |  Taxable: ₹${"%.2f".format(it.taxableValue)}"
                )
            }
            4 -> report.cdnur.map {
                Pair(
                    "${it.noteNumber}  ·  ${it.noteDate}  [${it.noteType}]",
                    "UR Type: ${it.urType}  |  Rate: ${it.rate}%  |  Taxable: ₹${"%.2f".format(it.taxableValue)}"
                )
            }
            5 -> report.hsnB2B.map {
                Pair(
                    "HSN: ${it.hsn}  (${it.uqc})  |  Rate: ${it.rate}%",
                    "${it.description}  |  Qty: ${it.totalQuantity}  |  Taxable: ₹${"%.2f".format(it.taxableValue)}"
                )
            }
            6 -> report.hsnB2C.map {
                Pair(
                    "HSN: ${it.hsn}  (${it.uqc})  |  Rate: ${it.rate}%",
                    "${it.description}  |  Qty: ${it.totalQuantity}  |  Taxable: ₹${"%.2f".format(it.taxableValue)}"
                )
            }
            7 -> report.docs.map {
                Pair(
                    it.natureOfDoc,
                    "From: ${it.srFrom}  To: ${it.srTo}  |  Total: ${it.totalNumber}  Cancelled: ${it.cancelled}"
                )
            }
            8 -> report.eco.map {
                Pair(
                    "${it.ecoName}  (${it.ecoGstin})",
                    "Nature: ${it.natureOfSupply}  |  Net: ₹${"%.2f".format(it.netValue)}"
                )
            }
            9 -> report.ecoB2B.map {
                Pair(
                    "${it.docNumber}  ·  ${it.docDate}",
                    "Supplier: ${it.supplierGstin}  →  ${it.recipientGstin}  |  Rate: ${it.rate}%"
                )
            }
            10 -> report.ecoB2C.map {
                Pair(
                    "Supplier: ${it.supplierGstin}",
                    "POS: ${it.placeOfSupply}  |  Rate: ${it.rate}%  |  Taxable: ₹${"%.2f".format(it.taxableValue)}"
                )
            }
            11 -> report.ecoUrp2B.map {
                Pair(
                    "${it.docNumber}  ·  ${it.docDate}",
                    "Recipient: ${it.recipientGstin}  |  Rate: ${it.rate}%  |  Value: ₹${"%.2f".format(it.taxableValue)}"
                )
            }
            12 -> report.ecoUrp2C.map {
                Pair(
                    "POS: ${it.placeOfSupply}  |  Rate: ${it.rate}%",
                    "Taxable: ₹${"%.2f".format(it.taxableValue)}  |  Cess: ₹${"%.2f".format(it.cessAmount)}"
                )
            }
            else -> emptyList()
        }
}
