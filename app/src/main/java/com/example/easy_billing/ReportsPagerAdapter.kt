package com.example.easy_billing

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.easy_billing.fragments.*

class ReportsPagerAdapter(activity: AppCompatActivity) :
    FragmentStateAdapter(activity) {

    override fun getItemCount() = 5

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> OverviewFragment()
            1 -> ChartsFragment()
            2 -> PeakHoursFragment()
            3 -> ReportsFragment()
            else -> ProductsFragment()
        }
    }
}