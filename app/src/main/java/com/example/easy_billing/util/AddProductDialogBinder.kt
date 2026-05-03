package com.example.easy_billing.util

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.core.widget.addTextChangedListener
import com.example.easy_billing.R
import com.example.easy_billing.repository.ProductRepository
import com.example.easy_billing.repository.ProductVerificationRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds the new HSN-help + autofill + global-verification +
 * product-level-tax behaviour onto an inflated `dialog_add_product`
 * layout instance.
 *
 *   • HSN help button       → opens cbic-gst.gov.in.
 *   • Local autofill        → fills HSN + CGST/SGST/IGST from the
 *                             local shop_product table on name/HSN
 *                             entry (offline-friendly).
 *   • Variant dropdown      → fetched from the global backend
 *                             endpoint (`products/{name}/variants`).
 *                             Falls back to local distinct variants
 *                             when offline.
 *   • HSN backend check     → debounced call to /products/verify-hsn;
 *                             updates the TextInputLayout helper /
 *                             error state.
 *   • Mirror three pcts → legacy etGstRate (kept hidden so older
 *                          read sites stay sane).
 */
object AddProductDialogBinder {

    /** Debounce window for HSN backend verification. */
    private const val HSN_DEBOUNCE_MS = 600L

    fun bind(
        dialogView: View,
        scope: CoroutineScope,
        productRepo: ProductRepository,
        verificationRepo: ProductVerificationRepository,
        nameSource: () -> String,
        onAutofill: (hsn: String?, cgst: Double, sgst: Double, igst: Double) -> Unit = { _,_,_,_ -> }
    ) {
        val etHsn   = dialogView.findViewById<EditText>(R.id.etHsnCode)
        val etCgst  = dialogView.findViewById<EditText>(R.id.etCgst)
        val etSgst  = dialogView.findViewById<EditText>(R.id.etSgst)
        val etIgst  = dialogView.findViewById<EditText>(R.id.etIgst)
        val etGst   = dialogView.findViewById<EditText>(R.id.etGstRate)
        val btnHelp = dialogView.findViewById<MaterialButton>(R.id.btnHsnHelp)
        val etVariant = dialogView.findViewById<AutoCompleteTextView>(R.id.etVariantName)

        // 1. HSN help → browser
        btnHelp?.setOnClickListener { HsnHelpLauncher.open(it.context) }

        // 2. Mirror three percentages → legacy single field.
        val mirror = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val cgst = etCgst.text?.toString()?.toDoubleOrNull() ?: 0.0
                val sgst = etSgst.text?.toString()?.toDoubleOrNull() ?: 0.0
                val igst = etIgst.text?.toString()?.toDoubleOrNull() ?: 0.0
                val combined = if (igst > 0) igst else cgst + sgst
                etGst.setText(if (combined > 0) combined.toString() else "")
            }
        }
        etCgst.addTextChangedListener(mirror)
        etSgst.addTextChangedListener(mirror)
        etIgst.addTextChangedListener(mirror)

        // 3. HSN: debounced backend verify + local autofill.
        val hsnTextInputLayout = etHsn.parent.parent as? TextInputLayout
        var hsnJob: Job? = null
        etHsn.addTextChangedListener { editable ->
            val hsn = editable?.toString()?.trim().orEmpty()
            // Local autofill (offline-friendly): pull tax + variant
            // from history when the HSN code matches a previous row.
            if (hsn.length >= 4) {
                scope.launch {
                    val match = withContext(Dispatchers.IO) {
                        productRepo.autoFillFromHistory(hsn = hsn)
                    }
                    match ?: return@launch
                    applyAutofill(match.hsnCode, match.cgstPercentage,
                        match.sgstPercentage, match.igstPercentage,
                        etHsn, etCgst, etSgst, etIgst, onAutofill)
                }
            }

            // Backend verify (debounced; offline tolerated).
            hsnJob?.cancel()
            hsnTextInputLayout?.error = null
            hsnTextInputLayout?.helperText = null
            if (hsn.length < 4) return@addTextChangedListener
            hsnJob = scope.launch {
                delay(HSN_DEBOUNCE_MS)
                val result = withContext(Dispatchers.IO) {
                    verificationRepo.verifyHsn(hsn)
                }
                result.onSuccess { resp ->
                    if (resp.valid) {
                        hsnTextInputLayout?.error = null
                        hsnTextInputLayout?.helperText =
                            resp.description?.takeIf { it.isNotBlank() } ?: "HSN verified"
                    } else {
                        hsnTextInputLayout?.error =
                            resp.message ?: "HSN not found in registry"
                    }
                }.onFailure {
                    // Offline / network — leave the field neutral.
                    hsnTextInputLayout?.helperText = null
                }
            }
        }
    }

    /**
     * Explicit autofill — call after the user has picked or typed
     * a product name. Also fetches the global variant list and
     * pushes it into the variant dropdown.
     */
    fun triggerNameAutofill(
        dialogView: View,
        scope: CoroutineScope,
        productRepo: ProductRepository,
        verificationRepo: ProductVerificationRepository,
        name: String,
        onAutofill: (hsn: String?, cgst: Double, sgst: Double, igst: Double) -> Unit = { _,_,_,_ -> }
    ) {
        if (name.isBlank()) return
        val etHsn  = dialogView.findViewById<EditText>(R.id.etHsnCode)
        val etCgst = dialogView.findViewById<EditText>(R.id.etCgst)
        val etSgst = dialogView.findViewById<EditText>(R.id.etSgst)
        val etIgst = dialogView.findViewById<EditText>(R.id.etIgst)
        val etVariant = dialogView.findViewById<AutoCompleteTextView>(R.id.etVariantName)

        // Local autofill from past shop_product rows
        scope.launch {
            val match = withContext(Dispatchers.IO) {
                productRepo.autoFillFromHistory(name = name)
            }
            match ?: return@launch
            applyAutofill(match.hsnCode, match.cgstPercentage,
                match.sgstPercentage, match.igstPercentage,
                etHsn, etCgst, etSgst, etIgst, onAutofill)
        }

        // Variant dropdown — backend first, fall back to local
        // distinct variants if offline.
        scope.launch {
            val online = withContext(Dispatchers.IO) {
                verificationRepo.variantsFor(name).getOrNull()?.variants
            }
            val variants: List<String> = online ?: withContext(Dispatchers.IO) {
                productRepo.distinctVariants()
            }
            if (variants.isNotEmpty()) {
                etVariant.setAdapter(
                    ArrayAdapter(
                        etVariant.context,
                        android.R.layout.simple_list_item_1,
                        variants.map { it.firstCapital() }
                    )
                )
            }
        }
    }

    /* ------------------------------------------------------------------ */

    private fun applyAutofill(
        hsn: String?, cgst: Double, sgst: Double, igst: Double,
        etHsn: EditText, etCgst: EditText, etSgst: EditText, etIgst: EditText,
        onAutofill: (hsn: String?, cgst: Double, sgst: Double, igst: Double) -> Unit
    ) {
        if (etHsn.text.isNullOrBlank() && !hsn.isNullOrBlank()) etHsn.setText(hsn)
        if (etCgst.text.isNullOrBlank() && cgst > 0) etCgst.setText(cgst.toString())
        if (etSgst.text.isNullOrBlank() && sgst > 0) etSgst.setText(sgst.toString())
        if (etIgst.text.isNullOrBlank() && igst > 0) etIgst.setText(igst.toString())
        onAutofill(hsn, cgst, sgst, igst)
    }

    private fun String.firstCapital(): String =
        trim().split(Regex("\\s+")).joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word.first().uppercaseChar() + word.drop(1)
        }
}
