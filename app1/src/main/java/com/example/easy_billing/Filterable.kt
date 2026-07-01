package com.example.easy_billing

interface Filterable {

    fun onFilterChanged(
        filter: ReportFilter,
        startDate: String? = null,
        endDate: String? = null
    )
}