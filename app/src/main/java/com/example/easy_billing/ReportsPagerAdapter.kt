package com.example.easy_billing

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.easy_billing.fragments.*

// 🔥 RESTRUCTURE: 5 tabs → 4. The old "Charts" tab duplicated the Sales
// tab's own trend chart (both showed revenue-over-time). ReportsFragment
// already has a Daily/Monthly toggle + bar chart + best/average metrics +
// a full ranked breakdown list, so it now IS the "Trends" tab and Charts
// is retired rather than kept as a near-duplicate screen.
class ReportsPagerAdapter(activity: AppCompatActivity) :
    FragmentStateAdapter(activity) {

    override fun getItemCount() = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> OverviewFragment()
            1 -> ReportsFragment()      // "Trends" — bar chart with back/next period arrows
            2 -> PeakHoursFragment()    // "Busy Times" — hour-by-hour breakdown
            else -> ProductsFragment()  // "Best Sellers"
        }
    }
}
