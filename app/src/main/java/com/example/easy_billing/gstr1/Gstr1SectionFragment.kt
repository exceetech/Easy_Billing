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
import com.example.easy_billing.util.CurrencyHelper

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
                // Sections with a purpose-built renderer, so the fields that
                // decide GST treatment stay visible instead of being flattened
                // into an anonymous text pair. 0 = B2B (Table 4), 1 = B2CL
                // (Table 5), 2 = B2CS (Table 7). The rest still use the
                // generic renderer below.
                val (count, adapter) = when (position) {
                    0 -> (report?.b2b?.size ?: 0) to
                            report?.b2b?.let { Gstr1B2bAdapter(it) }
                    1 -> (report?.b2cl?.size ?: 0) to
                            report?.b2cl?.let { Gstr1B2clAdapter(it) }
                    // The shop's own state code is the first 2 digits of its
                    // GSTIN — B2CS needs it to tell intra-state from inter.
                    2 -> (report?.b2cs?.size ?: 0) to
                            report?.b2cs?.let {
                                Gstr1B2csAdapter(it, report.gstin.take(2))
                            }
                    3 -> (report?.cdnr?.size ?: 0) to
                            report?.cdnr?.let { Gstr1CdnrAdapter(it) }
                    4 -> (report?.cdnur?.size ?: 0) to
                            report?.cdnur?.let { Gstr1CdnurAdapter(it) }
                    // 5 = HSN(B2B), 6 = HSN(B2C) — same Table 12 row shape,
                    // differing only in which invoices fed them.
                    5 -> (report?.hsnB2B?.size ?: 0) to
                            report?.hsnB2B?.let { Gstr1HsnAdapter(it) }
                    6 -> (report?.hsnB2C?.size ?: 0) to
                            report?.hsnB2C?.let { Gstr1HsnAdapter(it) }
                    7 -> (report?.docs?.size ?: 0) to
                            report?.docs?.let { Gstr1DocsAdapter(it) }
                    // 8-12 = the e-commerce-operator family (Tables 14 & 15).
                    8 -> (report?.eco?.size ?: 0) to
                            report?.eco?.let { Gstr1EcoAdapter(it) }
                    9 -> (report?.ecoB2B?.size ?: 0) to
                            report?.ecoB2B?.let { Gstr1EcoB2bAdapter(it) }
                    10 -> (report?.ecoB2C?.size ?: 0) to
                            report?.ecoB2C?.let { Gstr1EcoB2cAdapter(it) }
                    11 -> (report?.ecoUrp2B?.size ?: 0) to
                            report?.ecoUrp2B?.let { Gstr1EcoUrp2bAdapter(it) }
                    12 -> (report?.ecoUrp2C?.size ?: 0) to
                            report?.ecoUrp2C?.let { Gstr1EcoUrp2cAdapter(it) }
                    else -> 0 to null
                }
                if (adapter != null) {
                    if (count == 0) {
                        tvEmpty.visibility = View.VISIBLE
                        rvRows.visibility  = View.GONE
                        tvEmpty.text = getString(R.string.gstr1_no_records)
                        tvRowCount.text = getString(R.string.gstr1_zero_rows_caps)
                    } else {
                        tvEmpty.visibility = View.GONE
                        rvRows.visibility  = View.VISIBLE
                        tvRowCount.text    = "$count ROWS"
                        rvRows.adapter = adapter
                    }
                    return@collectLatest
                }

                val rows: List<Pair<String, String>> = report?.let { buildRows(it, position) } ?: emptyList()

                if (rows.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    rvRows.visibility  = View.GONE
                    // Round 2 Phase 1 originally showed a special "not
                    // available yet" warning on the 5 ECO tabs here,
                    // because at that point the backend genuinely didn't
                    // compute e-commerce-operator data and an empty ECO tab
                    // was indistinguishable from "not supported." Round 2
                    // Phase 2 then implemented real ECO computation
                    // server-side (see Gstr1Repository.fetchGstr1Online),
                    // so an empty ECO tab today means the ordinary thing —
                    // no e-commerce-operator sales that period — the same
                    // as any other empty section. The special-cased warning
                    // was reverted here (Round 3) because it had become
                    // actively wrong: it told the user a working feature
                    // was unavailable. isEcoTab() is kept (unused for now)
                    // as a documented marker of which positions are the
                    // ECO family, in case a future regression needs it.
                    tvEmpty.text = getString(R.string.gstr1_no_records)
                    tvRowCount.text = getString(R.string.gstr1_zero_rows)
                } else {
                    tvEmpty.visibility = View.GONE
                    rvRows.visibility  = View.VISIBLE
                    tvRowCount.text    = "${rows.size} row(s)"
                    rvRows.adapter = Gstr1RowAdapter(rows)
                }
            }
        }
    }

    /** Positions 8-12 are the ECO / ECO-B2B / ECO-B2C / ECOURP-B2B / ECOURP-B2C tabs. */
    private fun isEcoTab(position: Int): Boolean = position in 8..12

    /**
     * Returns list of (primary, secondary) string pairs for display.
     * Position maps to [Gstr1SheetTabAdapter.TAB_LABELS].
     */
    private fun buildRows(report: Gstr1Report, position: Int): List<Pair<String, String>> {
        val symbol = CurrencyHelper.getCurrencySymbol(requireContext())
        return try {
        when (position) {
            0 -> report.b2b.map {
                Pair(
                    "${it.invoiceNumber}  ·  ${it.invoiceDate}",
                    "GSTIN: ${it.gstin}  |  Rate: ${it.rate}%  |  Taxable: $symbol${"%.2f".format(it.taxableValue)}"
                )
            }
            1 -> report.b2cl.map {
                Pair(
                    "${it.invoiceNumber}  ·  ${it.invoiceDate}",
                    "POS: ${it.placeOfSupply}  |  Rate: ${it.rate}%  |  Taxable: $symbol${"%.2f".format(it.taxableValue)}"
                )
            }
            2 -> report.b2cs.map {
                Pair(
                    "POS: ${it.placeOfSupply}  |  Rate: ${it.rate}%",
                    "Type: ${it.type}  |  Taxable: $symbol${"%.2f".format(it.taxableValue)}"
                )
            }
            3 -> report.cdnr.map {
                Pair(
                    "${it.noteNumber}  ·  ${it.noteDate}  [${it.noteType}]",
                    "GSTIN: ${it.gstin}  |  Rate: ${it.rate}%  |  Taxable: $symbol${"%.2f".format(it.taxableValue)}"
                )
            }
            4 -> report.cdnur.map {
                Pair(
                    "${it.noteNumber}  ·  ${it.noteDate}  [${it.noteType}]",
                    "UR Type: ${it.urType}  |  Rate: ${it.rate}%  |  Taxable: $symbol${"%.2f".format(it.taxableValue)}"
                )
            }
            5 -> report.hsnB2B.map {
                Pair(
                    "HSN: ${it.hsn}  (${it.uqc})  |  Rate: ${it.rate}%",
                    "${it.description}  |  Qty: ${it.totalQuantity}  |  Taxable: $symbol${"%.2f".format(it.taxableValue)}"
                )
            }
            6 -> report.hsnB2C.map {
                Pair(
                    "HSN: ${it.hsn}  (${it.uqc})  |  Rate: ${it.rate}%",
                    "${it.description}  |  Qty: ${it.totalQuantity}  |  Taxable: $symbol${"%.2f".format(it.taxableValue)}"
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
                    "Nature: ${it.natureOfSupply}  |  Net: $symbol${"%.2f".format(it.netValue)}"
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
                    "POS: ${it.placeOfSupply}  |  Rate: ${it.rate}%  |  Taxable: $symbol${"%.2f".format(it.taxableValue)}"
                )
            }
            11 -> report.ecoUrp2B.map {
                Pair(
                    "${it.docNumber}  ·  ${it.docDate}",
                    "Recipient: ${it.recipientGstin}  |  Rate: ${it.rate}%  |  Value: $symbol${"%.2f".format(it.taxableValue)}"
                )
            }
            12 -> report.ecoUrp2C.map {
                Pair(
                    "POS: ${it.placeOfSupply}  |  Rate: ${it.rate}%",
                    "Taxable: $symbol${"%.2f".format(it.taxableValue)}  |  Cess: $symbol${"%.2f".format(it.cessAmount)}"
                )
            }
            else -> emptyList()
        }
        } catch (e: Exception) {
            com.example.easy_billing.util.UserEventLogger.logError(
                "Gstr1SectionFragment", "build_rows_failed: ${e.javaClass.simpleName}"
            )
            emptyList()
        }
    }
}
