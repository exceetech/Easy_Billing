package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.TextView
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
 * Read-only Profile screen.
 *
 * Shows two cards:
 *   • Store info (name, owner / legal name, contact, address)
 *   • GST profile (GSTIN, legal & trade names, registration type,
 *     scheme, state code)
 *
 * No editing happens here — store info is edited in
 * [StoreSettingsActivity], and the GST profile in
 * [BillingSettingsActivity]. Tax percentages have moved to the
 * product level and are no longer displayed on this screen.
 */
class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private val viewModel: ProfileViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rows: Map<Int, RowViews>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        rows = listOf(
            R.id.rowStoreName,
            R.id.rowOwner,
            R.id.rowPhone,
            R.id.rowAddress,
            R.id.rowGstin,
            R.id.rowLegalName,
            R.id.rowTradeName,
            R.id.rowRegType,
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
        rows[R.id.rowGstin]?.label?.setText(R.string.profile_label_gstin)
        rows[R.id.rowLegalName]?.label?.setText(R.string.profile_label_legal_name)
        rows[R.id.rowTradeName]?.label?.setText(R.string.profile_label_trade_name)
        rows[R.id.rowRegType]?.label?.setText(R.string.profile_label_reg_type)
        rows[R.id.rowScheme]?.label?.setText(R.string.profile_label_scheme)
        rows[R.id.rowState]?.label?.setText(R.string.profile_label_state)

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
                            rows[R.id.rowStoreName]?.value?.text = store?.name.orDash()
                            rows[R.id.rowOwner]?.value?.text =
                                store?.legalName?.takeIf { it.isNotBlank() }
                                    ?.let { it } ?: store?.name.orDash()
                            rows[R.id.rowPhone]?.value?.text   = store?.phone.orDash()
                            rows[R.id.rowAddress]?.value?.text = store?.address.orDash()

                            rows[R.id.rowGstin]?.value?.text     = gst?.gstin.orDash()
                            rows[R.id.rowLegalName]?.value?.text = gst?.legalName.orDash()
                            rows[R.id.rowTradeName]?.value?.text = gst?.tradeName.orDash()
                            rows[R.id.rowRegType]?.value?.text   = gst?.registrationType.orDash()
                            rows[R.id.rowScheme]?.value?.text    = gst?.gstScheme.orDash()
                            rows[R.id.rowState]?.value?.text     = gst?.stateCode.orDash()
                        }
                }
            }
        }
    }

    private fun String?.orDash(): String =
        if (this.isNullOrBlank()) "—" else this

    private data class RowViews(val label: TextView, val value: TextView)

    companion object {
        fun newInstance(): ProfileFragment = ProfileFragment()
    }
}
