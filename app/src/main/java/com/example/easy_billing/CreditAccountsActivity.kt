package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Button
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.CreditAccount
import com.example.easy_billing.db.CreditTransaction
import com.example.easy_billing.network.CreateCreditAccountRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.sync.SyncManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CreditAccountsActivity : BaseActivity() {

    private lateinit var rvCustomers: RecyclerView
    private lateinit var etSearch: TextInputEditText
    private lateinit var btnAdd: MaterialButton

    private lateinit var adapter: CreditAdapter
    private val list = mutableListOf<CreditAccount>()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var runnable: Runnable? = null

    private var currentFilter = "ALL"

    // 🔥 reuse DB instance
    private val db by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credit_accounts)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        initViews()

        setupRecycler()
        setupCardClicks()
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
        adapter = CreditAdapter(list) { account ->
            showAccountOptions(account)
        }
        rvCustomers.layoutManager = LinearLayoutManager(this)
        rvCustomers.adapter = adapter
    }

    private val shopId by lazy {
        getSharedPreferences("auth", MODE_PRIVATE)
            .getInt("SHOP_ID", 1)
    }

    // ================= LOAD =================

    private fun loadAccounts() = lifecycleScope.launch {

        val data = db.creditAccountDao().getAll(shopId)

        list.clear()
        list.addAll(data)

        adapter.notifyDataSetChanged()

        applyFilter(data)        // ✅ apply filter
        updateSummary(data)  // ✅ IMPORTANT
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
                            db.creditAccountDao().getAll(shopId)
                        } else {
                            db.creditAccountDao().search("%$query%", shopId)
                        }

                        list.clear()
                        list.addAll(result)
                        adapter.notifyDataSetChanged()
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

        val etName = view.findViewById<EditText>(R.id.etName)
        val etPhone = view.findViewById<EditText>(R.id.etPhone)
        val btnSave = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave)
        val btnCancel = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        // 🔥 make it look like premium popup
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {

            val name = etName.text.toString().trim()
            val phone = etPhone.text.toString().trim()

            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Enter all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {

                val db = AppDatabase.getDatabase(this@CreditAccountsActivity)

                val existing = db.creditAccountDao().getByPhone(phone, shopId)

                if (existing != null) {

                    if (!existing.isActive) {

                        withContext(Dispatchers.Main) {

                            dialog.dismiss()

                            AlertDialog.Builder(this@CreditAccountsActivity)
                                .setTitle("Restore Customer")
                                .setMessage(
                                    "This customer was deleted.\n\n" +
                                            "Old Name: ${existing.name}\n" +
                                            "New Name: $name\n\n" +
                                            "Do you want to restore?"
                                )
                                .setPositiveButton("Restore") { _, _ ->

                                    lifecycleScope.launch(Dispatchers.IO) {

                                        db.creditAccountDao().restoreAccount(
                                            phone = phone,
                                            name = name,
                                            isSynced = false,
                                            shopId = shopId
                                        )

                                        withContext(Dispatchers.Main) {

                                            loadAccounts()

                                            lifecycleScope.launch {
                                                val syncManager = SyncManager(this@CreditAccountsActivity)
                                                syncManager.syncAccounts()
                                                syncManager.syncCredit()
                                            }

                                            Toast.makeText(
                                                this@CreditAccountsActivity,
                                                "Customer restored",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }

                        return@launch
                    } else {

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@CreditAccountsActivity,
                                "Customer already exists",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        return@launch
                    }
                }

                val api = RetrofitClient.api

                val token = getSharedPreferences("auth", MODE_PRIVATE)
                    .getString("TOKEN", null)

                if (token == null) {
                    db.creditAccountDao().insert(
                        CreditAccount(
                            name = name,
                            phone = phone,
                            isSynced = false,
                            shopId = shopId
                        )
                    )
                    return@launch
                }

                try {
                    val response = api.createCreditAccount(
                        "Bearer $token",
                        CreateCreditAccountRequest(name, phone)
                    )

                    db.creditAccountDao().insert(
                        CreditAccount(
                            name = response.name,
                            phone = response.phone,
                            dueAmount = response.due_amount,
                            serverId = response.id,
                            isSynced = true,
                            shopId = shopId
                        )
                    )

                    println("✅ Created account: ${response.id}")

                } catch (e: Exception) {

                    e.printStackTrace()

                    db.creditAccountDao().insert(
                        CreditAccount(
                            name = name,
                            phone = phone,
                            isSynced = false,
                            shopId = shopId
                        )
                    )
                }

                withContext(Dispatchers.Main) {

                    val updated = db.creditAccountDao().getAll(shopId)

                    list.clear()
                    list.addAll(updated)
                    adapter.notifyDataSetChanged()

                    Toast.makeText(this@CreditAccountsActivity, "Customer added", Toast.LENGTH_SHORT).show()

                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    // ================= ACCOUNT OPTIONS =================

    private fun showAccountOptions(account: CreditAccount) {

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_account_options, null)

        val tvName = view.findViewById<TextView>(R.id.tvCustomerName)
        val btnAdd = view.findViewById<LinearLayout>(R.id.optionAdd)
        val btnSettle = view.findViewById<LinearLayout>(R.id.optionSettle)
        val btnView = view.findViewById<LinearLayout>(R.id.optionView)
        val btnDelete = view.findViewById<LinearLayout>(R.id.optionDelete)


        tvName.text = account.name

        btnAdd.setOnClickListener {
            dialog.dismiss()
            showPaymentDialog(account)
        }

        btnSettle.setOnClickListener {
            dialog.dismiss()
            settleAccount(account)
        }

        btnView.setOnClickListener {
            dialog.dismiss()
            openTransactions(account)
        }

        btnDelete.setOnClickListener {
            dialog.dismiss()
            deleteAccount(account)
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun openTransactions(account: CreditAccount) {

        val intent = Intent(this, CustomerTransactionsActivity::class.java)

        intent.putExtra("ACCOUNT_ID", account.serverId)
        intent.putExtra("ACCOUNT_NAME", account.name)
        intent.putExtra("ACCOUNT_PHONE", account.phone)

        startActivity(intent)
    }

    // ================= PAYMENT =================

    private fun showPaymentDialog(account: CreditAccount) {

        val view = layoutInflater.inflate(R.layout.dialog_add_payment, null)

        val tvName = view.findViewById<TextView>(R.id.tvName)
        val etAmount = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAmount)
        val btnCancel = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)
        val btnPay = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPay)

        tvName.text = account.name

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnPay.setOnClickListener {

            val amount = etAmount.text.toString().toDoubleOrNull()

            if (amount == null || amount <= 0) {
                etAmount.error = "Enter valid amount"
                return@setOnClickListener
            }

            dialog.dismiss()

            lifecycleScope.launch {

                val newDue = account.dueAmount - amount

                db.creditAccountDao().updateDue(account.id, newDue, shopId)

                db.creditTransactionDao().insert(
                    CreditTransaction(
                        accountId = account.id,
                        amount = amount,
                        type = "PAY",
                        shopId = shopId
                    )
                )

                loadAccounts()
                SyncManager(this@CreditAccountsActivity).syncCredit()
            }
        }

        dialog.show()
    }

    // ================= SETTLE =================

    private fun settleAccount(account: CreditAccount) {

        val dialogView = layoutInflater.inflate(R.layout.dialog_settle_account, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirm)

        tvTitle.text = "Settle Account"
        tvMessage.text = "Clear all dues for ${account.name}?"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {

            dialog.dismiss()

            lifecycleScope.launch {

                db.creditAccountDao().updateDue(account.id, 0.0, shopId)

                db.creditTransactionDao().insert(
                    CreditTransaction(
                        accountId = account.id,
                        amount = 0.0,
                        type = "SETTLE",
                        shopId = shopId
                    )
                )

                loadAccounts()
                SyncManager(this@CreditAccountsActivity).syncCredit()
            }
        }

        dialog.show()
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

    private fun updateSummary(accounts: List<CreditAccount>) {

        val totalDue = accounts.filter { it.dueAmount > 0 }.sumOf { it.dueAmount }
        val totalAdvance = accounts.filter { it.dueAmount < 0 }.sumOf { -it.dueAmount }

        val dueCount = accounts.count { it.dueAmount > 0 }
        val advanceCount = accounts.count { it.dueAmount < 0 }
        val settledCount = accounts.count { it.dueAmount == 0.0 }

        val net = totalDue - totalAdvance

        findViewById<TextView>(R.id.tvTotalDue).text = "₹$totalDue"
        findViewById<TextView>(R.id.tvTotalAdvance).text = "₹$totalAdvance"
        findViewById<TextView>(R.id.tvNetBalance).text = "₹$net"

        findViewById<TextView>(R.id.tvDueCount).text = "$dueCount"
        findViewById<TextView>(R.id.tvAdvanceCount).text = "$advanceCount"
        findViewById<TextView>(R.id.tvSettledCount).text = "$settledCount"
    }

    private fun applyFilter(accounts: List<CreditAccount>) {

        val filtered = when (currentFilter) {
            "DUE" -> accounts.filter { it.dueAmount > 0 }
            "ADVANCE" -> accounts.filter { it.dueAmount < 0 }
            "SETTLED" -> accounts.filter { it.dueAmount == 0.0 }
            else -> accounts
        }

        adapter.update(filtered)
    }

    private fun setupCardClicks() {

        findViewById<View>(R.id.cardDue).setOnClickListener {
            toggleFilter("DUE", it)
        }

        findViewById<View>(R.id.cardAdvance).setOnClickListener {
            toggleFilter("ADVANCE", it)
        }

        findViewById<View>(R.id.cardNet).setOnClickListener {
            toggleFilter("ALL", it)
        }

        findViewById<View>(R.id.cardCustomers).setOnClickListener {
            showFilterOptions()
        }
    }

    private fun toggleFilter(filter: String, view: View) {

        currentFilter = if (currentFilter == filter) {
            "ALL"
        } else {
            filter
        }

        loadAccounts()

        if (currentFilter == "ALL") {
            resetCardHighlight()
        } else {
            highlightCard(view)
        }
    }

    private fun resetCardHighlight() {

        val cards = listOf(
            R.id.cardDue,
            R.id.cardAdvance,
            R.id.cardNet,
            R.id.cardCustomers
        )

        cards.forEach {
            findViewById<View>(it).alpha = 1.0f
        }
    }

    private fun showFilterOptions() {

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_filter_customers, null)

        val btnAll = view.findViewById<LinearLayout>(R.id.optionAll)
        val btnDue = view.findViewById<LinearLayout>(R.id.optionDue)
        val btnAdvance = view.findViewById<LinearLayout>(R.id.optionAdvance)
        val btnSettled = view.findViewById<LinearLayout>(R.id.optionSettled)

        fun apply(filter: String) {
            currentFilter = filter
            loadAccounts()
            dialog.dismiss()
        }

        btnAll.setOnClickListener { apply("ALL") }
        btnDue.setOnClickListener { apply("DUE") }
        btnAdvance.setOnClickListener { apply("ADVANCE") }
        btnSettled.setOnClickListener { apply("SETTLED") }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun highlightCard(selected: View) {

        val cards = listOf(
            R.id.cardDue,
            R.id.cardAdvance,
            R.id.cardNet,
            R.id.cardCustomers
        )

        cards.forEach {
            findViewById<View>(it).alpha = 0.6f
        }

        selected.alpha = 1.0f
    }

    private fun deleteAccount(account: CreditAccount) {

        if (account.dueAmount != 0.0) {
            Toast.makeText(this, "Only settled accounts can be removed", Toast.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_delete_account, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        val message = view.findViewById<TextView>(R.id.tvMessage)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemove)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnOk = view.findViewById<MaterialButton>(R.id.btnOk)

        // ================= NO INTERNET =================
        if (!isInternetAvailable()) {

            message.text = "Internet is required to remove ${account.name}"

            btnRemove.visibility = View.GONE   // 🔥 hide remove
            btnCancel.visibility = View.GONE   // 🔥 hide cancel
            btnOk.visibility = View.VISIBLE    // 🔥 show only OK

            btnOk.setOnClickListener {
                dialog.dismiss()
            }

        }
        // ================= INTERNET AVAILABLE =================
        else {

            message.text = "You're about to remove ${account.name}\n\n• Account will be removed from your database"

            btnOk.visibility = View.GONE       // 🔥 hide OK
            btnRemove.visibility = View.VISIBLE
            btnCancel.visibility = View.VISIBLE

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            btnRemove.setOnClickListener {

                lifecycleScope.launch(Dispatchers.IO) {

                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)

                    val api = RetrofitClient.api

                    try {
                        if (token != null && account.serverId != -1) {
                            api.deactivateCreditAccount("Bearer $token", account.serverId!!)
                        }

                        db.creditAccountDao().deactivate(account.id, shopId)

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        loadAccounts()

                        Toast.makeText(
                            this@CreditAccountsActivity,
                            "Account removed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        dialog.show()
    }
}