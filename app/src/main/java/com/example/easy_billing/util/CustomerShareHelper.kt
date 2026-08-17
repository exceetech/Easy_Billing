package com.example.easy_billing.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.easy_billing.R
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.db.GstSalesInvoice
import com.example.easy_billing.db.StoreInfo
import com.example.easy_billing.repository.PosPaymentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Send to customer" — turns an invoice into a direct SMS to the
 * customer's number carrying a UPI payment link. Reuses
 * [InvoicePdfGenerator] with printAfterSave=false (no system print
 * dialog) and [PosPaymentRepository] for the Razorpay link + hosted-PDF
 * URL.
 *
 * Sent straight via [SmsManager] — no messaging app opened at all, see
 * [sendViaSms]. That needs the SEND_SMS (+ READ_PHONE_STATE) runtime
 * permissions; [hasSmsPermission] lets the calling Activity check/request
 * them BEFORE invoking [sendToCustomer], since a permission prompt can
 * only be driven from an Activity, not from here.
 *
 * WhatsApp was removed from this flow: a regular WhatsApp install has no
 * API for a silent, no-app-opened send — that requires the separate
 * WhatsApp Business Cloud API (Meta business verification, a dedicated
 * business number, pre-approved message templates, per-message cost),
 * which is a different integration entirely, not a permission this app
 * can just request. If that's ever wanted, it needs its own backend
 * integration project, not a change here.
 */
object CustomerShareHelper {

    /** READ_PHONE_STATE is required alongside SEND_SMS — see AndroidManifest.xml comment. */
    val SMS_PERMISSIONS = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)

    fun hasSmsPermission(context: Context): Boolean =
        SMS_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * True only when there's an active SIM capable of sending SMS —
     * checked once, at bind time, to decide whether "Send to customer"
     * appears at all. A WiFi-only tablet with no SIM slot/card has no
     * path to SMS ever working (SmsManager needs a real carrier line,
     * no app or permission can substitute for that), so on those
     * devices this whole feature is hidden rather than shown and then
     * failing — falls back to the pre-existing save-invoice/print flow,
     * unchanged. TelephonyManager.simState doesn't need a runtime
     * permission to read, just the SIM's basic ready/absent state.
     */
    fun hasActiveSim(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        return tm.simState == TelephonyManager.SIM_STATE_READY
    }

    /** Returns true on success (SMS sent); false and shows its own Toast on failure. */
    suspend fun sendToCustomer(
        context: Context,
        bill: Bill,
        billItems: List<BillItem>,
        storeInfo: StoreInfo?,
        gstScheme: String?,
        gstInvoice: GstSalesInvoice?,
        printerLayout: String,
        customerName: String?,
        customerPhone: String?
    ): Boolean {
        if (bill.billNumber.isBlank()) {
            Toast.makeText(context, context.getString(R.string.send_to_customer_bill_not_synced), Toast.LENGTH_LONG).show()
            return false
        }

        val pdfFile = try {
            withContext(Dispatchers.IO) {
                InvoicePdfGenerator.generatePdfFromBill(
                    context, bill, billItems, storeInfo, gstScheme, gstInvoice,
                    printerLayout, printAfterSave = false
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
        val pdfUrl = PosPaymentRepository.uploadInvoicePdf(context, bill.billNumber, pdfFile)

        return sendViaSms(context, customerPhone, bill.billNumber, amountText, payLinkUrl, pdfUrl)
    }

    /**
     * Sends the message straight to [phone] via [SmsManager] — no
     * messaging app opened. Caller (the Activity) must have already
     * confirmed [hasSmsPermission]; this still checks defensively so we
     * never crash on a SecurityException if that contract is violated.
     */
    private fun sendViaSms(context: Context, phone: String?, billNumber: String, amountText: String, payLinkUrl: String?, pdfUrl: String?): Boolean {
        if (phone.isNullOrBlank()) {
            Toast.makeText(context, context.getString(R.string.send_to_customer_sms_no_phone), Toast.LENGTH_LONG).show()
            return false
        }

        if (!hasSmsPermission(context)) {
            Toast.makeText(context, context.getString(R.string.send_to_customer_sms_permission_needed), Toast.LENGTH_LONG).show()
            return false
        }

        val message = when {
            payLinkUrl != null && pdfUrl != null ->
                context.getString(R.string.send_to_customer_sms_message_with_pdf, billNumber, amountText, pdfUrl, payLinkUrl)
            payLinkUrl != null ->
                context.getString(R.string.send_to_customer_sms_message, billNumber, amountText, payLinkUrl)
            pdfUrl != null ->
                context.getString(R.string.send_to_customer_sms_message_no_pay, billNumber, amountText, pdfUrl)
            else ->
                context.getString(R.string.send_to_customer_sms_message_no_pay_no_pdf, billNumber, amountText)
        }

        return try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone.trim(), null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phone.trim(), null, message, null, null)
            }
            Toast.makeText(context, context.getString(R.string.send_to_customer_sms_sent), Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            // Surface the real reason instead of a generic message — the
            // usual culprits on a physical device are: no SIM / no active
            // mobile line (IllegalStateException / "unable to send"), or a
            // dual-SIM phone with no default SIM chosen for SMS (throws a
            // SecurityException even though the permission is granted).
            android.util.Log.e("CustomerShareHelper", "sendViaSms failed", e)
            Toast.makeText(
                context,
                context.getString(R.string.send_to_customer_sms_failed) + " (${e.javaClass.simpleName}: ${e.message})",
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }
}
