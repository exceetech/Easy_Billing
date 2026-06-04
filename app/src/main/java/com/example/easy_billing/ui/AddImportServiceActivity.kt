package com.example.easy_billing.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
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
    private val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

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
                tvInvoiceDate.text = "Date: ${sdf.format(selectedDateEpoch)}"
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }

        // Setup Spinners
        val spinnerPos = findViewById<Spinner>(R.id.spinnerPos)
        val posAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(
            "97 - Other Territory",
            "96 - Foreign Country"
        ))
        spinnerPos.adapter = posAdapter

        val spinnerItc = findViewById<Spinner>(R.id.spinnerItcEligibility)
        val itcAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(
            "Inputs",
            "Capital goods",
            "Input services",
            "Ineligible"
        ))
        spinnerItc.adapter = itcAdapter

        findViewById<View>(R.id.toolbar).setOnClickListener { finish() }

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

    private fun saveRecord() {
        val invoiceNumber = findViewById<TextInputEditText>(R.id.etInvoiceNumber).text.toString().trim()
        val invoiceValue = findViewById<TextInputEditText>(R.id.etInvoiceValue).text.toString().toDoubleOrNull() ?: 0.0
        val rate = findViewById<TextInputEditText>(R.id.etRate).text.toString().toDoubleOrNull() ?: 0.0
        val taxableValue = findViewById<TextInputEditText>(R.id.etTaxableValue).text.toString().toDoubleOrNull() ?: 0.0
        val igstPaid = findViewById<TextInputEditText>(R.id.etIgstPaid).text.toString().toDoubleOrNull() ?: 0.0
        val cessPaid = findViewById<TextInputEditText>(R.id.etCessPaid).text.toString().toDoubleOrNull() ?: 0.0
        val availedIgst = findViewById<TextInputEditText>(R.id.etAvailedItcIgst).text.toString().toDoubleOrNull() ?: 0.0
        val availedCess = findViewById<TextInputEditText>(R.id.etAvailedItcCess).text.toString().toDoubleOrNull() ?: 0.0
        
        val pos = findViewById<Spinner>(R.id.spinnerPos).selectedItem.toString()
        val itc = findViewById<Spinner>(R.id.spinnerItcEligibility).selectedItem.toString()

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
