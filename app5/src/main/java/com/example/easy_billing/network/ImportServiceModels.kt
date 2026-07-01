package com.example.easy_billing.network

data class ImportServiceDto(
    val local_id: Int?,
    val invoice_number: String,
    val invoice_date: Long,
    val invoice_value: Double,
    val place_of_supply: String,
    val rate: Double,
    val taxable_value: Double,
    val igst_paid: Double,
    val cess_paid: Double,
    val eligibility_for_itc: String,
    val availed_itc_igst: Double,
    val availed_itc_cess: Double,
    val sync_status: String,
    val device_id: String?
)

data class ImportServiceResponseDto(
    val id: Int,
    val shop_id: Int,
    val local_id: Int?,
    val invoice_number: String,
    val invoice_date: Long,
    val invoice_value: Double,
    val place_of_supply: String,
    val rate: Double,
    val taxable_value: Double,
    val igst_paid: Double,
    val cess_paid: Double,
    val eligibility_for_itc: String,
    val availed_itc_igst: Double,
    val availed_itc_cess: Double,
    val sync_status: String,
    val device_id: String?
)
