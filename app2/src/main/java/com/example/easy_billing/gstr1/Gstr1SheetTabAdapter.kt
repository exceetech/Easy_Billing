package com.example.easy_billing.gstr1

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager2 adapter for the 13 GSTR-1 section tabs.
 *
 * Each tab hosts a [Gstr1SectionFragment] that holds a RecyclerView
 * showing that section's rows.
 */
class Gstr1SheetTabAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        val TAB_LABELS = listOf(
            "B2B", "B2CL", "B2CS", "CDNR", "CDNUR",
            "HSN-B2B", "HSN-B2C", "DOCS",
            "ECO", "ECO-B2B", "ECO-B2C", "ECOURP-B2B", "ECOURP-B2C"
        )
    }

    // Current report data — fragments update themselves when this changes
    private var report: Gstr1Report? = null

    private val fragments = mutableListOf<Gstr1SectionFragment>()

    override fun getItemCount() = TAB_LABELS.size

    override fun createFragment(position: Int): Fragment {
        return Gstr1SectionFragment.newInstance(position)
    }
}
