package com.example.easy_billing.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.example.easy_billing.R
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.db.GstSalesInvoice
import com.example.easy_billing.db.StoreInfo
import com.example.easy_billing.repository.PosPaymentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Send to customer" — opens WhatsApp with a message to the customer's
 * number already filled in (bill number, amount, and a UPI pay link when
 * one applies), via the wa.me deep link. Reuses [InvoicePdfGenerator]
 * with printAfterSave=false (no system print dialog) and
 * [PosPaymentRepository] for the Razorpay link + hosted-PDF URL.
 *
 * This is a real WhatsApp compose screen the cashier still has to tap
 * Send on — there's no silent, no-app-opened API for a regular WhatsApp
 * install (that requires the separate WhatsApp Business Cloud API: Meta
 * business verification, a dedicated business number, pre-approved
 * message templates, per-message cost — a different integration
 * entirely). Since the target user already lives in WhatsApp day to day,
 * one extra tap to hit Send there is the simplest, most familiar option
 * — simpler than the old SMS path, in fact: no SEND_SMS/READ_PHONE_STATE
 * runtime permission, and no dependency on the device having an active
 * SIM (works on a WiFi-only device too, as long as WhatsApp is installed).
 */
object CustomerShareHelper {

    /** True when WhatsApp (regular or Business) is installed on this device. */
    fun isWhatsAppInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return listOf("com.whatsapp", "com.whatsapp.w4b").any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /** Returns true if WhatsApp was opened successfully; false and shows its own Toast on failure. */
    suspend fun sendToCustomer(
        context: Context,
        bill: Bill,
        billItems: List<BillItem>,
        storeInfo: StoreInfo?,
        gstScheme: String?,
        gstInvoice: GstSalesInvoice?,
        printerLayout: String,
        customerName: String?,
        customerPhone: String?,
        totalCess: Double = 0.0
    ): Boolean {
        if (bill.billNumber.isBlank()) {
            Toast.makeText(context, context.getString(R.string.send_to_customer_bill_not_synced), Toast.LENGTH_LONG).show()
            return false
        }

        val pdfFile = try {
            withContext(Dispatchers.IO) {
                InvoicePdfGenerator.generatePdfFromBill(
                    context, bill, billItems, storeInfo, gstScheme, gstInvoice,
                    printerLayout, printAfterSave = false, totalCess = totalCess
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, context.getString(R.string.send_to_customer_pdf_failed), Toast.LENGTH_LONG).show()
            return false
        }

        // A payment link only makes sense for a UPI sale that's still
        // unpaid — every other payment_method (Cash/Card/Credit) was
        // already settled at the register, and a UPI sale that's already
        // paid (webhook/mark-as-paid landed after the invoice was
        // generated) has nothing left to collect either. Either way
        // "send to customer" then is just the invoice, no pay-now link.
        val isUpi = bill.paymentMethod.equals("UPI", ignoreCase = true)
        val alreadyPaid = bill.paymentStatus.equals("paid", ignoreCase = true)
        val needsPaymentLink = isUpi && !alreadyPaid

        val payLinkUrl: String? = if (needsPaymentLink) {
            val link = PosPaymentRepository.createPaymentLink(context, bill.billNumber, customerName, customerPhone)
            if (link == null) {
                Toast.makeText(context, context.getString(R.string.send_to_customer_link_failed), Toast.LENGTH_LONG).show()
                return false
            }
            link.payment_link_url
        } else null

        val amountText = CurrencyHelper.format(context, bill.total)
        // Still uploaded for the backend's own record/view-invoice link,
        // even though the WhatsApp message itself only needs payLinkUrl.
        PosPaymentRepository.uploadInvoicePdf(context, bill.billNumber, pdfFile)

        return openWhatsAppChat(context, customerPhone, amountText, bill.billNumber, payLinkUrl)
    }

    /**
     * Opens the customer's WhatsApp chat directly (via the wa.me deep
     * link) with the message already typed in — the cashier just taps
     * WhatsApp's own Send button. Needs the customer's phone number
     * (10-digit Indian mobile numbers are assumed and given the +91
     * country code automatically); anything already carrying a country
     * code (11+ digits) is passed through as-is.
     */
    private fun openWhatsAppChat(context: Context, phone: String?, amountText: String, billNumber: String, payLinkUrl: String?): Boolean {
        if (phone.isNullOrBlank()) {
            Toast.makeText(context, context.getString(R.string.send_to_customer_sms_no_phone), Toast.LENGTH_LONG).show()
            return false
        }

        if (!isWhatsAppInstalled(context)) {
            Toast.makeText(context, context.getString(R.string.send_to_customer_whatsapp_not_installed), Toast.LENGTH_LONG).show()
            return false
        }

        val message = if (payLinkUrl != null)
            context.getString(R.string.send_to_customer_whatsapp_message, billNumber, amountText, payLinkUrl)
        else
            context.getString(R.string.send_to_customer_whatsapp_message_no_pay, billNumber, amountText)

        val digitsOnly = phone.filter { it.isDigit() }
        val internationalPhone = if (digitsOnly.length == 10) "91$digitsOnly" else digitsOnly

        return try {
            val uri = Uri.parse("https://wa.me/$internationalPhone?text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            android.util.Log.e("CustomerShareHelper", "openWhatsAppChat failed", e)
            Toast.makeText(context, context.getString(R.string.send_to_customer_whatsapp_not_installed), Toast.LENGTH_LONG).show()
            false
        }
    }
}
