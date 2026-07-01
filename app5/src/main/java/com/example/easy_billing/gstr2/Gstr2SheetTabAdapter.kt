package com.example.easy_billing.gstr2

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.easy_billing.gstr1.Gstr1SectionFragment
import com.google.gson.Gson

class Gstr2SheetTabAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private var report: Gstr2Report? = null
    private val gson = Gson()

    fun updateReport(newReport: Gstr2Report) {
        this.report = newReport
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = TAB_LABELS.size

    override fun createFragment(position: Int): Fragment {
        return Gstr2SectionFragment.newInstance(position)
    }

    companion object {
        val TAB_LABELS = arrayOf(
            "B2B", "B2BUR", "IMPS", "IMPG", "CDNR", "CDNUR", "EXEMP", "HSNSUM"
        )
    }
}
