package com.example.easy_billing.gstr2

import android.util.Log
import com.example.easy_billing.network.ApiService
import kotlinx.coroutines.flow.Flow

class Gstr2Repository(
    private val api: ApiService,
    private val draftDao: Gstr2DraftDao
) {
    suspend fun fetchGstr2(token: String, startDate: String, endDate: String): Gstr2Report {
        Log.d("Gstr2Repository", "Fetching GSTR-2 for $startDate to $endDate")
        val response = api.getGstr2(token, startDate, endDate)
        
        return Gstr2Report(
            gstin = "", // Set from profile later
            financialYear = "", 
            period = "",
            returnType = "",
            generatedAt = System.currentTimeMillis(),
            b2b = response.b2b.map { item ->
                Gstr2B2bRow(
                    supplierGstin = item.supplier_gstin,
                    invoiceNumber = item.invoice_number,
                    invoiceDate = item.invoice_date,
                    invoiceValue = item.invoice_value,
                    placeOfSupply = item.place_of_supply,
                    reverseCharge = item.reverse_charge,
                    invoiceType = item.invoice_type,
                    rate = item.rate,
                    taxableValue = item.taxable_value,
                    igstPaid = item.igst,
                    cgstPaid = item.cgst,
                    sgstPaid = item.sgst,
                    cessPaid = item.cess,
                    eligibilityForItc = item.itc_eligibility,
                    availedItcIgst = item.availed_itc_igst,
                    availedItcCgst = item.availed_itc_cgst,
                    availedItcSgst = item.availed_itc_sgst,
                    availedItcCess = item.availed_itc_cess
                )
            },
            b2bur = response.b2bur.map { item ->
                Gstr2B2burRow(
                    supplierName = item.supplier_name,
                    invoiceNumber = item.invoice_number,
                    invoiceDate = item.invoice_date,
                    invoiceValue = item.invoice_value,
                    placeOfSupply = item.place_of_supply,
                    supplyType = item.supply_type,
                    rate = item.rate,
                    taxableValue = item.taxable_value,
                    igstPaid = item.igst,
                    cgstPaid = item.cgst,
                    sgstPaid = item.sgst,
                    cessPaid = item.cess,
                    eligibilityForItc = item.itc_eligibility,
                    availedItcIgst = item.availed_itc_igst,
                    availedItcCgst = item.availed_itc_cgst,
                    availedItcSgst = item.availed_itc_sgst,
                    availedItcCess = item.availed_itc_cess
                )
            },
            imps = response.imps.map { item ->
                Gstr2ImpsRow(
                    invoiceNumber = item.invoice_number,
                    invoiceDate = item.invoice_date,
                    invoiceValue = item.invoice_value,
                    placeOfSupply = item.place_of_supply,
                    rate = item.rate,
                    taxableValue = item.taxable_value,
                    igstPaid = item.igst,
                    cessPaid = item.cess,
                    eligibilityForItc = item.itc_eligibility,
                    availedItcIgst = item.availed_itc_igst,
                    availedItcCess = item.availed_itc_cess
                )
            },
            impg = response.impg.map { item ->
                Gstr2ImpgRow(
                    portCode = item.port_code,
                    billOfEntryNumber = item.bill_of_entry_number,
                    billOfEntryDate = item.bill_of_entry_date,
                    billOfEntryValue = item.bill_of_entry_value,
                    documentType = item.document_type,
                    sezSupplierGstin = item.sez_supplier_gstin,
                    rate = item.rate,
                    taxableValue = item.taxable_value,
                    igstPaid = item.igst,
                    cessPaid = item.cess,
                    eligibilityForItc = item.itc_eligibility,
                    availedItcIgst = item.availed_itc_igst,
                    availedItcCess = item.availed_itc_cess
                )
            },
            cdnr = response.cdnr.map { item ->
                Gstr2CdnrRow(
                    supplierGstin = item.supplier_gstin,
                    noteNumber = item.note_number,
                    noteDate = item.note_date,
                    invoiceNumber = item.invoice_number,
                    invoiceDate = item.invoice_date,
                    preGst = item.pre_gst,
                    documentType = item.document_type,
                    reason = item.reason,
                    supplyType = item.supply_type,
                    noteValue = item.note_value,
                    rate = item.rate,
                    taxableValue = item.taxable_value,
                    igstPaid = item.igst,
                    cgstPaid = item.cgst,
                    sgstPaid = item.sgst,
                    cessPaid = item.cess,
                    eligibilityForItc = item.itc_eligibility,
                    availedItcIgst = item.availed_itc_igst,
                    availedItcCgst = item.availed_itc_cgst,
                    availedItcSgst = item.availed_itc_sgst,
                    availedItcCess = item.availed_itc_cess
                )
            },
            cdnur = response.cdnur.map { item ->
                Gstr2CdnurRow(
                    noteNumber = item.note_number,
                    noteDate = item.note_date,
                    invoiceNumber = item.invoice_number,
                    invoiceDate = item.invoice_date,
                    preGst = item.pre_gst,
                    documentType = item.document_type,
                    reason = item.reason,
                    supplyType = item.supply_type,
                    invoiceType = item.invoice_type,
                    noteValue = item.note_value,
                    rate = item.rate,
                    taxableValue = item.taxable_value,
                    igstPaid = item.igst,
                    cgstPaid = item.cgst,
                    sgstPaid = item.sgst,
                    cessPaid = item.cess,
                    eligibilityForItc = item.itc_eligibility,
                    availedItcIgst = item.availed_itc_igst,
                    availedItcCgst = item.availed_itc_cgst,
                    availedItcSgst = item.availed_itc_sgst,
                    availedItcCess = item.availed_itc_cess
                )
            },
            exemp = response.exemp.map { item ->
                Gstr2ExempRow(
                    description = item.description,
                    composition = item.composition,
                    nilRated = item.nil_rated,
                    exempted = item.exempted,
                    nonGst = item.non_gst
                )
            },
            hsnsum = response.hsnsum.map { item ->
                Gstr2HsnsumRow(
                    hsn = item.hsn,
                    description = item.description,
                    uqc = item.uqc,
                    totalQuantity = item.total_quantity,
                    totalValue = item.total_value,
                    taxableValue = item.taxable_value,
                    igstAmount = item.igst,
                    cgstAmount = item.cgst,
                    sgstAmount = item.sgst,
                    cessAmount = item.cess
                )
            }
        )
    }

    suspend fun saveDraft(draft: Gstr2DraftEntity) {
        draftDao.insertOrUpdate(draft)
    }

    suspend fun deleteDraft(draft: Gstr2DraftEntity) {
        draftDao.delete(draft)
    }

    fun getAllDrafts(): Flow<List<Gstr2DraftEntity>> {
        return draftDao.getAllDrafts()
    }
}
