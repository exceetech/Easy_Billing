package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.network.CreatePaymentLinkRequest
import com.example.easy_billing.network.CreatePaymentLinkResponse
import com.example.easy_billing.network.CreateQrCodeResponse
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * "Send to customer" — creates a Razorpay Payment Link and hosts the
 * already-generated invoice PDF at a short-lived public URL, so the app
 * can hand both to a WhatsApp/SMS share intent. See
 * app/routes/pos_payment_routes.py on the backend for what actually
 * creates/serves these.
 *
 * Nothing here sends any message — see SendToCustomerSheet for the
 * share-intent construction.
 */
object PosPaymentRepository {

    private fun token(context: Context): String? =
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null)

    /**
     * Uploads [pdfFile] and returns its temporary public URL, or null on
     * any failure (network, auth, server error) — callers should treat
     * null as "SMS's PDF link isn't available right now", not crash the
     * whole send flow, since WhatsApp sharing doesn't need this at all
     * (it attaches the local file directly).
     */
    suspend fun uploadInvoicePdf(context: Context, billNumber: String, pdfFile: File): String? =
        withContext(Dispatchers.IO) {
            val tok = token(context) ?: return@withContext null
            try {
                val body = pdfFile.asRequestBody("application/pdf".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", pdfFile.name, body)
                RetrofitClient.api.uploadInvoicePdf("Bearer $tok", billNumber, part).pdf_url
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    /**
     * Creates (or reuses, per the backend's own idempotency) a Razorpay
     * Payment Link for this bill's current total. Returns null on any
     * failure — callers should surface a clear "couldn't create the
     * payment link" message rather than silently sending an invoice with
     * no way to pay.
     */
    suspend fun createPaymentLink(
        context: Context,
        billNumber: String,
        customerName: String? = null,
        customerPhone: String? = null
    ): CreatePaymentLinkResponse? = withContext(Dispatchers.IO) {
        val tok = token(context) ?: return@withContext null
        try {
            RetrofitClient.api.createPaymentLink(
                "Bearer $tok",
                billNumber,
                CreatePaymentLinkRequest(customer_name = customerName, customer_phone = customerPhone)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Scan-to-pay in-person alternative to createPaymentLink — no SIM,
     * no phone number, the app just shows the returned QR image on
     * screen for the customer to scan with whatever UPI app they have.
     * Returns null on any failure, same contract as createPaymentLink.
     */
    suspend fun createQrCode(context: Context, billNumber: String): CreateQrCodeResponse? =
        withContext(Dispatchers.IO) {
            val tok = token(context) ?: return@withContext null
            try {
                RetrofitClient.api.createQrCode("Bearer $tok", billNumber)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    /**
     * Manual "mark as paid" override for the cashier — used when a
     * customer has genuinely paid (verified independently, e.g. by
     * checking the Razorpay dashboard or the customer's own UPI app)
     * but the webhook that would normally flip this automatically
     * hasn't landed. Returns the server's confirmed payment_status, or
     * null on any failure (network, auth, bill not found).
     */
    suspend fun markPaid(context: Context, billNumber: String): String? = withContext(Dispatchers.IO) {
        val tok = token(context) ?: return@withContext null
        try {
            RetrofitClient.api.markBillPaid("Bearer $tok", billNumber).payment_status
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
