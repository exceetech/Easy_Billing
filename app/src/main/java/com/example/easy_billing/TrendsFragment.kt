package com.example.easy_billing.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.example.easy_billing.R

/**
 * Trends tab = the line chart first (ChartsFragment), followed by the
 * Daily/Monthly bar chart and breakdown (ReportsFragment). Both children
 * implement Filterable; ReportsActivity forwards date-chip changes to the
 * children of each pager fragment, so no extra wiring is needed.
 */
class TrendsFragment : Fragment(R.layout.fragment_trends_host) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.trendsChartContainer, ChartsFragment())
                .replace(R.id.trendsSalesContainer, ReportsFragment())
                .commit()
        }
    }
}
