package com.example.easy_billing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.easy_billing.viewmodel.ProfileViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Read-only Profile screen (cream / white theme).
 *
 * Hero card (store avatar + name + owner + GST/phone chips), a Store information
 * card and a GST profile card. Editing happens elsewhere; this screen only displays.
 */
class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private val viewModel: ProfileViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rows: Map<Int, RowViews>

    private lateinit var tvAvatar: TextView
    private lateinit var tvStoreName: TextView
    private lateinit var tvOwnerLine: TextView
    private lateinit var chipGst: View
    private lateinit var chipPhone: View
    private lateinit var tvPhoneChip: TextView
    private lateinit var tvGstinValue: TextView
    private lateinit var tvRegTypeBadge: TextView
    private var currentGstin: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipeRefresh = view.findViewById(R.id.swipeRefresh)

        tvAvatar = view.findViewById(R.id.tvAvatar)
        tvStoreName = view.findViewById(R.id.tvStoreName)
        tvOwnerLine = view.findViewById(R.id.tvOwnerLine)
        chipGst = view.findViewById(R.id.chipGst)
        chipPhone = view.findViewById(R.id.chipPhone)
        tvPhoneChip = view.findViewById(R.id.tvPhoneChip)
        tvGstinValue = view.findViewById(R.id.tvGstinValue)
        tvRegTypeBadge = view.findViewById(R.id.tvRegTypeBadge)

        rows = listOf(
            R.id.rowStoreName,
            R.id.rowOwner,
            R.id.rowPhone,
            R.id.rowAddress,
            R.id.rowLegalName,
            R.id.rowTradeName,
            R.id.rowScheme,
            R.id.rowState
        ).associateWith { id ->
            val row = view.findViewById<View>(id)
            RowViews(
                label = row.findViewById(R.id.label),
                value = row.findViewById(R.id.value)
            )
        }

        rows[R.id.rowStoreName]?.label?.setText(R.string.profile_label_store_name)
        rows[R.id.rowOwner]?.label?.setText(R.string.profile_label_owner)
        rows[R.id.rowPhone]?.label?.setText(R.string.profile_label_phone)
        rows[R.id.rowAddress]?.label?.setText(R.string.profile_label_address)
        rows[R.id.rowLegalName]?.label?.setText(R.string.profile_label_legal_name)
        rows[R.id.rowTradeName]?.label?.setText(R.string.profile_label_trade_name)
        rows[R.id.rowScheme]?.label?.setText(R.string.profile_label_scheme)
        rows[R.id.rowState]?.label?.setText(R.string.profile_label_state)

        view.findViewById<View>(R.id.btnCopyGstin).setOnClickListener { copyGstin() }
        view.findViewById<View>(R.id.btnSignOut).setOnClickListener { signOut() }

        swipeRefresh.setOnRefreshListener { viewModel.refreshFromBackend() }

        observe()
        viewModel.refreshFromBackend()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        swipeRefresh.isRefreshing = state.loading
                    }
                }
                launch {
                    combine(viewModel.storeInfo, viewModel.gstProfile) { s, g -> s to g }
                        .collect { (store, gst) ->
                            // Rows
                            rows[R.id.rowStoreName]?.value?.text = store?.name.orDash()
                            rows[R.id.rowOwner]?.value?.text =
                                store?.legalName?.takeIf { it.isNotBlank() } ?: store?.name.orDash()
                            rows[R.id.rowPhone]?.value?.text   = store?.phone.orDash()
                            rows[R.id.rowAddress]?.value?.text = store?.address.orDash()

                            rows[R.id.rowLegalName]?.value?.text = gst?.legalName.orDash()
                            rows[R.id.rowTradeName]?.value?.text = gst?.tradeName.orDash()
                            rows[R.id.rowScheme]?.value?.text    = gst?.gstScheme.orDash()
                            rows[R.id.rowState]?.value?.text     = gst?.stateCode.orDash()

                            // Hero
                            val name = store?.name.orEmpty()
                            tvStoreName.text = name.ifBlank { "—" }
                            tvAvatar.text = initials(name)

                            val owner = store?.legalName?.takeIf { it.isNotBlank() } ?: store?.name
                            tvOwnerLine.text =
                                owner?.takeIf { it.isNotBlank() }?.let { "$it · Owner" } ?: "Owner"

                            val phone = store?.phone.orEmpty()
                            if (phone.isBlank()) {
                                chipPhone.visibility = View.GONE
                            } else {
                                chipPhone.visibility = View.VISIBLE
                                tvPhoneChip.text = phone
                            }

                            // GST
                            currentGstin = gst?.gstin.orEmpty()
                            tvGstinValue.text = currentGstin.ifBlank { "—" }
                            chipGst.visibility =
                                if (currentGstin.isNotBlank()) View.VISIBLE else View.GONE
                            tvRegTypeBadge.text =
                                gst?.registrationType?.takeIf { it.isNotBlank() } ?: "—"
                        }
                }
            }
        }
    }

    private fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "ST"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "${parts[0].first()}${parts[1].first()}".uppercase()
        }
    }

    private fun copyGstin() {
        if (currentGstin.isBlank()) return
        val cm = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("GSTIN", currentGstin))
        Toast.makeText(requireContext(), "GSTIN copied", Toast.LENGTH_SHORT).show()
    }

    private fun signOut() {
        com.example.easy_billing.ui.ThemedDropdown.showConfirm(
            context = requireContext(),
            title = "Sign out?",
            message = "You'll need to log in again to access your store.",
            confirmLabel = "Sign out"
        ) { performSignOut() }
    }

    private fun performSignOut() {
        requireContext().getSharedPreferences("auth", Context.MODE_PRIVATE)
            .edit().remove("TOKEN").apply()
        val intent = Intent(requireContext(), MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }

    private fun String?.orDash(): String =
        if (this.isNullOrBlank()) "—" else this

    private data class RowViews(val label: TextView, val value: TextView)

    companion object {
        fun newInstance(): ProfileFragment = ProfileFragment()
    }
}
