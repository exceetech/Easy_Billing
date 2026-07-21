package com.example.easy_billing.ui

import com.example.easy_billing.util.appNow

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.BaseActivity
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.ImportService
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Add — or edit — one GSTR-2 import-of-services record.
 *
 * Editing exists because the server can refuse a record for breaking a GSTR-2
 * rule. Without a way to correct it, a refused record was stranded: not on the
 * server, not fixable, invisible to the sync indicator, and destroyed by the
 * next workspace change. Pass [EXTRA_RECORD_ID] to open an existing record.
 */
class AddImportServiceActivity : BaseActivity() {

    companion object {
        /** Local row id to edit. Absent (or -1) means "add a new record". */
        const val EXTRA_RECORD_ID = "record_id"
    }

    private var selectedDateEpoch: Long = appNow()
    private var selectedItc: String = "Inputs"
    private var selectedPos: String = "97 - Other Territory"
    private val posOptions = listOf("97 - Other Territory", "96 - Foreign Country")
    private val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    /** The record being edited, or null when adding. */
    private var editing: ImportService? = null

    /**
     * Set while fields are being populated from an existing record, so the
     * auto-calculation watchers don't recompute IGST / invoice value from
     * half-filled inputs and overwrite what was actually saved.
     */
    private var populating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_import_service)

        val tvInvoiceDate = findViewById<TextView>(R.id.tvInvoiceDate)
        // No "Date: " prefix — the field already has an "Invoice date" label
        // above it, and the picker below set the text without one, so the
        // caption changed shape the first time you touched it.
        tvInvoiceDate.text = sdf.format(selectedDateEpoch)

        tvInvoiceDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = selectedDateEpoch
            
            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                selectedDateEpoch = calendar.timeInMillis
                tvInvoiceDate.text = sdf.format(selectedDateEpoch)
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }

        // Place of supply — themed dropdown field
        val tvPos = findViewById<TextView>(R.id.tvPos)
        tvPos.text = selectedPos
        tvPos.setOnClickListener { anchor -> showPosDropdown(anchor as TextView) }

        // ITC eligibility — segmented selector
        val segViews = listOf(
            findViewById<TextView>(R.id.tvItcInputs),
            findViewById<TextView>(R.id.tvItcCapital),
            findViewById<TextView>(R.id.tvItcServices),
            findViewById<TextView>(R.id.tvItcIneligible)
        )
        segViews.forEach { seg ->
            seg.setOnClickListener {
                selectedItc = seg.tag.toString()
                segViews.forEach { it.isSelected = (it == seg) }
            }
        }
        segViews.first().performClick()   // default to Inputs

        // Back arrow and its click handling come from the shared toolbar
        // helper, the same way the list screen gets them — rather than a
        // hand-rolled ImageView that had to be kept in step by hand.
        setupToolbar(R.id.toolbar)

        val etRate = findViewById<TextInputEditText>(R.id.etRate)
        val etTaxableValue = findViewById<TextInputEditText>(R.id.etTaxableValue)
        val etIgstPaid = findViewById<TextInputEditText>(R.id.etIgstPaid)
        val etCessPaid = findViewById<TextInputEditText>(R.id.etCessPaid)
        val etInvoiceValue = findViewById<TextInputEditText>(R.id.etInvoiceValue)

        var isAutoFilling = false

        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isAutoFilling || populating) return

                if (etRate.hasFocus() || etTaxableValue.hasFocus()) {
                    val rate = etRate.text.toString().toDoubleOrNull() ?: 0.0
                    val taxable = etTaxableValue.text.toString().toDoubleOrNull() ?: 0.0
                    val igst = taxable * rate / 100
                    isAutoFilling = true
                    etIgstPaid.setText(String.format(Locale.US, "%.2f", igst))
                    isAutoFilling = false
                }
                
                if (!etInvoiceValue.hasFocus()) {
                    val taxable = etTaxableValue.text.toString().toDoubleOrNull() ?: 0.0
                    val igst = etIgstPaid.text.toString().toDoubleOrNull() ?: 0.0
                    val cess = etCessPaid.text.toString().toDoubleOrNull() ?: 0.0
                    val invValue = taxable + igst + cess
                    isAutoFilling = true
                    etInvoiceValue.setText(String.format(Locale.US, "%.2f", invValue))
                    isAutoFilling = false
                }

                updateAvailedCaps()
            }
        }

        etRate.addTextChangedListener(textWatcher)
        etTaxableValue.addTextChangedListener(textWatcher)
        etIgstPaid.addTextChangedListener(textWatcher)
        etCessPaid.addTextChangedListener(textWatcher)

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            saveRecord()
        }

        // ── Edit mode ────────────────────────────────────────────────────
        val recordId = intent.getIntExtra(EXTRA_RECORD_ID, -1)
        if (recordId > 0) {
            lifecycleScope.launch {
                val dao = AppDatabase.getDatabase(this@AddImportServiceActivity).importServiceDao()
                val record = withContext(Dispatchers.IO) { dao.getById(recordId) }
                if (record == null) {
                    Toast.makeText(
                        this@AddImportServiceActivity,
                        "That record no longer exists", Toast.LENGTH_SHORT
                    ).show()
                    finish()
                    return@launch
                }
                editing = record
                populate(record, tvInvoiceDate, segViews)

                // The title is two views — plain word plus the serif accent —
                // so setting one to "Edit record" would leave "record" showing
                // twice.
                findViewById<TextView>(R.id.tvScreenTitle).text = "Edit this"
                findViewById<MaterialButton>(R.id.btnSave).text = "Save changes"

                // Delete only for records that never reached the server. There
                // is no delete endpoint, so removing a synced row locally would
                // leave it on the server for the next pull to restore.
                if (record.syncStatus != "synced") {
                    findViewById<View>(R.id.btnDelete).apply {
                        visibility = View.VISIBLE
                        setOnClickListener { confirmDelete(record) }
                    }
                }
            }
        }
    }

    /** Fills every field from an existing record, watchers muted. */
    private fun populate(r: ImportService, tvInvoiceDate: TextView, segViews: List<TextView>) {
        populating = true
        try {
            selectedDateEpoch = r.invoiceDate
            tvInvoiceDate.text = sdf.format(selectedDateEpoch)

            findViewById<TextInputEditText>(R.id.etInvoiceNumber).setText(r.invoiceNumber)
            findViewById<TextInputEditText>(R.id.etInvoiceValue).setText(num(r.invoiceValue))
            findViewById<TextInputEditText>(R.id.etRate).setText(num(r.rate))
            findViewById<TextInputEditText>(R.id.etTaxableValue).setText(num(r.taxableValue))
            findViewById<TextInputEditText>(R.id.etIgstPaid).setText(num(r.igstPaid))
            findViewById<TextInputEditText>(R.id.etCessPaid).setText(num(r.cessPaid))
            findViewById<TextInputEditText>(R.id.etAvailedItcIgst).setText(num(r.availedItcIgst))
            findViewById<TextInputEditText>(R.id.etAvailedItcCess).setText(num(r.availedItcCess))

            // Place of supply: keep the stored string even if it is not one of
            // the two current options, so editing an old record can't silently
            // rewrite a value the user never touched.
            selectedPos = r.placeOfSupply
            findViewById<TextView>(R.id.tvPos).text = selectedPos

            selectedItc = r.eligibilityForItc
            val match = segViews.firstOrNull { it.tag?.toString() == r.eligibilityForItc }
            segViews.forEach { it.isSelected = (it == match) }

            // The watchers are muted while populating, so the captions have to
            // be refreshed by hand once the fields are in.
            updateAvailedCaps()

            // The stored value isn't one of the four buttons — "None" can come
            // from the server. Keep it rather than rewriting it, but say so,
            // otherwise the row would read as having no eligibility at all.
            findViewById<TextView>(R.id.tvItcOther).apply {
                if (match == null) {
                    text = "Saved as “${r.eligibilityForItc}” — pick a button above to change it."
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
        } finally {
            populating = false
        }
    }

    private fun num(v: Double): String = String.format(Locale.US, "%.2f", v)

    /**
     * Keeps the "of ₹X paid" caption under each availed-ITC field in step with
     * the tax actually entered.
     *
     * The save already refuses a claim larger than the tax paid. Showing the
     * ceiling next to the input means the user sees the limit while typing
     * rather than finding out from a toast after tapping Save.
     */
    private fun updateAvailedCaps() {
        val igstPaid = findViewById<TextInputEditText>(R.id.etIgstPaid)
            .text.toString().toDoubleOrNull() ?: 0.0
        val cessPaid = findViewById<TextInputEditText>(R.id.etCessPaid)
            .text.toString().toDoubleOrNull() ?: 0.0

        findViewById<TextView>(R.id.tvAvailedIgstCap).text = "of ₹${num(igstPaid)} paid"
        findViewById<TextView>(R.id.tvAvailedCessCap).text = "of ₹${num(cessPaid)} paid"
    }

    private fun confirmDelete(record: ImportService) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete this record?")
            .setMessage(
                "Invoice ${record.invoiceNumber} has not been sent to the server, " +
                "so deleting it here removes it for good."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(this@AddImportServiceActivity)
                            .importServiceDao().deleteById(record.id)
                    }
                    Toast.makeText(this@AddImportServiceActivity, "Deleted", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Themed dropdown for Place of supply — rounded card, styled rows, check on selection. */
    private fun showPosDropdown(anchor: TextView) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }

        val popup = PopupWindow(
            container, anchor.width,
            ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        posOptions.forEach { opt ->
            val isSel = opt == selectedPos
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                )
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            val label = TextView(this).apply {
                text = opt
                textSize = 14f
                setTextColor(Color.parseColor(if (isSel) "#185FA5" else "#1A1A18"))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(label)
            if (isSel) {
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(Color.parseColor("#185FA5"))
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                })
            }
            row.setOnClickListener {
                selectedPos = opt
                anchor.text = opt
                popup.dismiss()
            }
            container.addView(row)
        }

        popup.showAsDropDown(anchor, 0, dp(6))
    }

    private fun saveRecord() {
        val invoiceNumber = findViewById<TextInputEditText>(R.id.etInvoiceNumber).text.toString().trim()
        val invoiceValue = findViewById<TextInputEditText>(R.id.etInvoiceValue).text.toString().toDoubleOrNull() ?: 0.0
        val rate = findViewById<TextInputEditText>(R.id.etRate).text.toString().toDoubleOrNull() ?: 0.0
        val taxableValue = findViewById<TextInputEditText>(R.id.etTaxableValue).text.toString().toDoubleOrNull() ?: 0.0
        val igstPaid = findViewById<TextInputEditText>(R.id.etIgstPaid).text.toString().toDoubleOrNull() ?: 0.0
        val cessPaid = findViewById<TextInputEditText>(R.id.etCessPaid).text.toString().toDoubleOrNull() ?: 0.0
        val availedIgst = findViewById<TextInputEditText>(R.id.etAvailedItcIgst).text.toString().toDoubleOrNull() ?: 0.0
        val availedCess = findViewById<TextInputEditText>(R.id.etAvailedItcCess).text.toString().toDoubleOrNull() ?: 0.0
        
        val pos = selectedPos
        val itc = selectedItc

        if (invoiceNumber.isEmpty()) {
            Toast.makeText(this, "Invoice Number is required", Toast.LENGTH_SHORT).show()
            return
        }

        // GSTR-2 rules, matching the ones Add Purchase already enforces per
        // line and again at header level. Without them an impossible record
        // saves and syncs cleanly, and is only rejected at filing time —
        // long after anyone could tell which entry was wrong.
        fun reject(message: String): Boolean {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return true
        }

        if (invoiceValue <= 0.0 && reject("Invoice value must be greater than 0")) return
        if (listOf(invoiceValue, rate, taxableValue, igstPaid, cessPaid,
                   availedIgst, availedCess).any { it < 0.0 } &&
            reject("Negative amounts are not allowed")) return

        // Same tolerance as PurchaseLineDialog: a claim can equal the tax
        // paid, and rounding must not turn that into a rejection.
        val eps = 0.011
        if (availedIgst > igstPaid + eps &&
            reject("Availed ITC IGST cannot exceed IGST paid (${"%.2f".format(igstPaid)})")) return
        if (availedCess > cessPaid + eps &&
            reject("Availed ITC Cess cannot exceed Cess paid (${"%.2f".format(cessPaid)})")) return

        if (itc in listOf("Ineligible", "None") &&
            (availedIgst > 0.01 || availedCess > 0.01) &&
            reject("Availed ITC must be 0 when eligibility is $itc")) return

        val current = editing
        val record = ImportService(
            // Keep the existing row's id when editing, so this updates in place
            // instead of leaving the old copy behind. The server upserts on
            // local_id, so the same id also updates the server row rather than
            // creating a second one.
            id = current?.id ?: 0,
            invoiceNumber = invoiceNumber,
            invoiceDate = selectedDateEpoch,
            invoiceValue = invoiceValue,
            placeOfSupply = pos,
            rate = rate,
            taxableValue = taxableValue,
            igstPaid = igstPaid,
            cessPaid = cessPaid,
            eligibilityForItc = itc,
            availedItcIgst = availedIgst,
            availedItcCess = availedCess,
            // Always back to "pending": an edited record has to be pushed
            // again, and a corrected one must leave "rejected" or it would
            // never be retried.
            syncStatus = "pending"
        )

        val dao = AppDatabase.getDatabase(this).importServiceDao()

        lifecycleScope.launch {
            // Warn — don't block — on a repeated invoice number. Nothing
            // enforces uniqueness here or on the server, so the same invoice
            // can be entered twice and its ITC claimed twice. A hard rule would
            // be wrong: two foreign suppliers can issue the same number.
            val clash = withContext(Dispatchers.IO) {
                dao.findByInvoiceNumber(invoiceNumber, record.id)
            }
            if (clash != null) {
                androidx.appcompat.app.AlertDialog.Builder(this@AddImportServiceActivity)
                    .setTitle("Invoice number already used")
                    .setMessage(
                        "Invoice ${clash.invoiceNumber} is already recorded, dated " +
                        "${sdf.format(clash.invoiceDate)}. Saving this creates a second " +
                        "entry, and its ITC would be claimed twice."
                    )
                    .setNegativeButton("Go back", null)
                    .setPositiveButton("Save anyway") { _, _ -> commit(record, dao, current != null) }
                    .show()
                return@launch
            }
            commit(record, dao, current != null)
        }
    }

    private fun commit(
        record: ImportService,
        dao: com.example.easy_billing.db.ImportServiceDao,
        isEdit: Boolean
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (isEdit) dao.update(record) else dao.insert(record)
            }
            Toast.makeText(
                this@AddImportServiceActivity,
                if (isEdit) "Changes saved" else "Saved successfully",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}
