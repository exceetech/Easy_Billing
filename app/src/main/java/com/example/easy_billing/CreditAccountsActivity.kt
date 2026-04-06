package com.example.easy_billing

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.CreditAccount
import com.example.easy_billing.db.CreditTransaction
import com.example.easy_billing.sync.SyncManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class CreditAccountsActivity : BaseActivity() {

    private lateinit var rvCustomers: RecyclerView
    private lateinit var etSearch: TextInputEditText
    private lateinit var btnAdd: MaterialButton

    private lateinit var adapter: CreditAdapter
    private val list = mutableListOf<CreditAccount>()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var runnable: Runnable? = null

    // 🔥 reuse DB instance
    private val db by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credit_accounts)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        initViews()

        setupRecycler()
        etSearch.clearFocus()
        setupOutsideTouch()
        setupSearch()
        setupAddButton()

        loadAccounts()
    }

    // ================= INIT =================

    private fun initViews() {
        rvCustomers = findViewById(R.id.rvCustomers)
        etSearch = findViewById(R.id.etSearchCustomer)
        btnAdd = findViewById(R.id.btnAddCustomer)
    }

    private fun setupRecycler() {
        adapter = CreditAdapter(list) { showAccountOptions(it) }
        rvCustomers.layoutManager = LinearLayoutManager(this)
        rvCustomers.adapter = adapter
    }

    // ================= LOAD =================

    private fun loadAccounts() = lifecycleScope.launch {
        val data = db.creditAccountDao().getAll()
        list.apply {
            clear()
            addAll(data)
        }
        adapter.notifyDataSetChanged()
    }

    // ================= SEARCH =================

    private fun setupSearch() {

        etSearch.clearFocus()

        etSearch.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                // Cancel previous search
                runnable?.let { handler.removeCallbacks(it) }

                runnable = Runnable {

                    val query = s?.toString()
                        ?.trim()
                        ?.take(50)
                        ?: ""

                    lifecycleScope.launch {

                        val db = AppDatabase.getDatabase(this@CreditAccountsActivity)

                        val result = if (query.isEmpty()) {
                            db.creditAccountDao().getAll()
                        } else {
                            db.creditAccountDao().search("%$query%")
                        }

                        adapter.update(result)
                    }
                }

                handler.postDelayed(runnable!!, 300)
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ================= ADD CUSTOMER =================

    private fun setupAddButton() {
        btnAdd.setOnClickListener { showAddCustomerDialog() }
    }

    private fun showAddCustomerDialog() {

        val view = layoutInflater.inflate(R.layout.dialog_add_customer, null)

        val etName = view.findViewById<TextInputEditText>(R.id.etName)
        val etPhone = view.findViewById<TextInputEditText>(R.id.etPhone)

        AlertDialog.Builder(this)
            .setTitle("Add Customer")
            .setView(view)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {

                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {

                        val name = etName.text.toString().trim()
                        val phone = etPhone.text.toString().trim()

                        if (name.isEmpty()) {
                            toast("Enter name"); return@setOnClickListener
                        }

                        if (phone.length < 10) {
                            toast("Enter valid phone"); return@setOnClickListener
                        }

                        lifecycleScope.launch {

                            val id = db.creditAccountDao().insert(
                                CreditAccount(name = name, phone = phone)
                            )

                            db.creditTransactionDao().insert(
                                CreditTransaction(
                                    accountId = id.toInt(),
                                    amount = 0.0,
                                    type = "ADD"
                                )
                            )

                            dismiss()
                            toast("Customer added")
                        }
                    }
                }
            }
            .show()
    }

    // ================= ACCOUNT OPTIONS =================

    private fun showAccountOptions(account: CreditAccount) {

        val options = arrayOf("Add Payment", "Settle Account")

        AlertDialog.Builder(this)
            .setTitle(account.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPaymentDialog(account)
                    1 -> settleAccount(account)
                }
            }
            .show()
    }

    // ================= PAYMENT =================

    private fun showPaymentDialog(account: CreditAccount) {

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        AlertDialog.Builder(this)
            .setTitle("Enter Payment Amount")
            .setView(input)
            .setPositiveButton("Pay") { _, _ ->

                val amount = input.text.toString().toDoubleOrNull()

                if (amount == null || amount <= 0) {
                    toast("Invalid amount")
                    return@setPositiveButton
                }

                lifecycleScope.launch {

                    val newDue = (account.dueAmount - amount).coerceAtLeast(0.0)

                    db.creditAccountDao().updateDue(account.id, newDue)

                    db.creditTransactionDao().insert(
                        CreditTransaction(
                            accountId = account.id,
                            amount = amount,
                            type = "PAY"
                        )
                    )

                    loadAccounts()
                    SyncManager(this@CreditAccountsActivity).syncCredit()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= SETTLE =================

    private fun settleAccount(account: CreditAccount) {

        AlertDialog.Builder(this)
            .setTitle("Settle Account")
            .setMessage("Clear all dues for ${account.name}?")
            .setPositiveButton("Yes") { _, _ ->

                lifecycleScope.launch {

                    db.creditAccountDao().updateDue(account.id, 0.0)

                    db.creditTransactionDao().insert(
                        CreditTransaction(
                            accountId = account.id,
                            amount = 0.0,
                            type = "SETTLE"
                        )
                    )

                    loadAccounts()
                    SyncManager(this@CreditAccountsActivity).syncCredit()
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    // ================= UTILS =================

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun setupOutsideTouch() {

        val root = findViewById<View>(android.R.id.content)

        root.setOnTouchListener { _, _ ->

            // Clear text
            etSearch.text?.clear()

            // Remove focus
            etSearch.clearFocus()

            // Hide keyboard
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)

            false
        }
    }
}