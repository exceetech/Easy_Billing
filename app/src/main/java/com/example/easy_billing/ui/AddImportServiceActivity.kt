package com.example.easy_billing.ui

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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

class AddImportServiceActivity : AppCompatActivity() {

    private var selectedDateEpoch: Long = System.currentTimeMillis()
    private var selectedItc: String = "Inputs"
    private var selectedPos: String = "97 - Other Territory"
    private val posOptions = listOf("97 - Other Territory", "96 - Foreign Country")
    private val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_import_service)

        val tvInvoiceDate = findViewById<TextView>(R.id.tvInvoiceDate)
        tvInvoiceDate.text = "Date: ${sdf.format(selectedDateEpoch)}"

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

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

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
                if (isAutoFilling) return
                
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
            }
        }

        etRate.addTextChangedListener(textWatcher)
        etTaxableValue.addTextChangedListener(textWatcher)
        etIgstPaid.addTextChangedListener(textWatcher)
        etCessPaid.addTextChangedListener(textWatcher)

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            saveRecord()
        }
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

        val record = ImportService(
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
            syncStatus = "pending"
        )

        val dao = AppDatabase.getDatabase(this).importServiceDao()
        
        lifecycleScope.launch(Dispatchers.IO) {
            dao.insert(record)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@AddImportServiceActivity, "Saved successfully", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
