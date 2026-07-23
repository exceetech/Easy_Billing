package com.example.easy_billing

import android.content.Intent
import android.graphics.Color
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
    private lateinit var tvCustomersEmpty: TextView
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

        if (!requireShop()) return

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
        tvCustomersEmpty = findViewById(R.id.tvCustomersEmpty)
        etSearch = findViewById(R.id.etSearchCustomer)
        btnAdd = findViewById(R.id.btnAddCustomer)
    }

    private fun setupRecycler() {
        adapter = CreditAdapter(list) { account ->
            showAccountOptions(account)
        }
        rvCustomers.layoutManager = LinearLayoutManager(this)
        rvCustomers.adapter = adapter

        // Rows draw square, so the first and last would overhang the list
        // card's rounded corners — most visibly the coloured balance stripe.
        // Set in code because android:clipToOutline is API 31+.
        rvCustomers.clipToOutline = true
    }

    /**
     * Defaults to -1, not 1. A missing shop id used to fall back to shop 1,
     * so a signed-out or half-initialised session wrote real customer balances
     * into another shop's books instead of refusing. -1 matches no row, and
     * [requireShop] closes the screen rather than letting writes through.
     */
    private val shopId by lazy {
        getSharedPreferences("auth", MODE_PRIVATE)
            .getInt("SHOP_ID", -1)
    }

    /** False (and closes the screen) when there is no valid shop to work in. */
    private fun requireShop(): Boolean {
        if (shopId > 0) return true
        Toast.makeText(this, "No shop selected. Sign in again.", Toast.LENGTH_SHORT).show()
        finish()
        return false
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

                        // Go through applyFilter / updateSummary rather than
                        // pushing straight into the adapter. Writing the list
                        // directly ignored an active Due / Advance / Settled
                        // filter — so searching quietly showed rows the filter
                        // excluded — and left the summary tiles reading the
                        // pre-search totals.
                        applyFilter(result)
                        updateSummary(result)
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

            // Digits-only, 10-digit check — matches the format phone lookups
            // elsewhere (billing, GST) expect. Anything shorter/longer or
            // containing letters would save fine here but silently fail to
            // match later, so catch it at entry with a specific message.
            val digitsOnly = phone.filter { it.isDigit() }
            if (digitsOnly.length != 10) {
                Toast.makeText(this, "Enter a valid 10-digit phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Guard against a double-tap firing two creates before the first
            // round trip finishes.
            btnSave.isEnabled = false
            btnSave.text = "Saving…"

            lifecycleScope.launch(Dispatchers.IO) {

                val db = AppDatabase.getDatabase(this@CreditAccountsActivity)

                val existing = db.creditAccountDao().getByPhone(phone, shopId)

                if (existing != null) {

                    if (!existing.isActive) {

                        withContext(Dispatchers.Main) {

                            dialog.dismiss()

                            showRestoreCustomerDialog(existing, name, phone)
                        }

                        return@launch
                    } else {

                        withContext(Dispatchers.Main) {
                            btnSave.isEnabled = true
                            btnSave.text = "Save"
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
                        token,
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

    /**
     * Shown when the phone number entered belongs to a customer that was
     * deleted. Was a stock AlertDialog with setTitle / setMessage, which made
     * it the only dialog here not in the app's own style.
     *
     * The two names sit in a comparison card rather than in the message text —
     * that they differ is the whole point of asking. Either name can be kept:
     * restoreAccount takes the name as an argument, so both buttons run the
     * same operation with a different one. Each button names the result rather
     * than saying only "Restore".
     *
     * When the typed name matches the saved one there is nothing to choose, so
     * the comparison and the second button are hidden.
     */
    private fun showRestoreCustomerDialog(
        existing: CreditAccount,
        newName: String,
        phone: String
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_restore_customer, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        // The card is the layout's own rounded background, so the dialog
        // window behind it must be transparent.
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.tvRestorePhone).text = phone

        val oldTrimmed = existing.name.trim()
        val newTrimmed = newName.trim()

        view.findViewById<TextView>(R.id.tvOldName).text = oldTrimmed
        view.findViewById<TextView>(R.id.tvNewName).text = newTrimmed
        view.findViewById<TextView>(R.id.tvOldAvatar).text =
            if (oldTrimmed.isNotEmpty()) oldTrimmed[0].uppercase() else "?"
        view.findViewById<TextView>(R.id.tvNewAvatar).text =
            if (newTrimmed.isNotEmpty()) newTrimmed[0].uppercase() else "?"

        // Only mention the balance when there is one. Deleting requires a
        // settled account, so this is usually zero — but it can be non-zero if
        // the balance moved on another device before the delete synced.
        view.findViewById<TextView>(R.id.tvRestoreNote).text =
            if (existing.dueAmount == 0.0)
                "Their past transactions come back with the account. The balance is settled."
            else
                "Their balance of ${money(kotlin.math.abs(existing.dueAmount))} comes back with the account."

        val btnRestore = view.findViewById<MaterialButton>(R.id.btnRestore)
        val btnKeepOld = view.findViewById<MaterialButton>(R.id.btnKeepOld)

        // Restoring under either name is the same operation with a different
        // argument, so both buttons run this.
        fun restoreWith(chosenName: String) {
            dialog.dismiss()

            lifecycleScope.launch(Dispatchers.IO) {

                db.creditAccountDao().restoreAccount(
                    phone = phone,
                    name = chosenName,
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
                        "$chosenName restored",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Same name typed as the one already saved — there is nothing to
        // choose between, so offer a single plain Restore.
        val namesDiffer = !oldTrimmed.equals(newTrimmed, ignoreCase = true)

        val compareCard = view.findViewById<View>(R.id.layoutNameCompare)

        if (namesDiffer) {
            compareCard.visibility = View.VISIBLE
            btnRestore.text = "Restore as $newTrimmed"
            btnKeepOld.text = "Keep \"$oldTrimmed\""
            btnKeepOld.visibility = View.VISIBLE
            btnKeepOld.setOnClickListener { restoreWith(oldTrimmed) }
        } else {
            // Nothing to compare, and no choice to make.
            compareCard.visibility = View.GONE
            btnRestore.text = "Restore customer"
            btnKeepOld.visibility = View.GONE
        }

        btnRestore.setOnClickListener { restoreWith(newTrimmed) }

        view.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }

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
        view.findViewById<TextView>(R.id.tvPhone).text = account.phone

        // Balance in the header: settle clears it, delete is blocked unless it
        // is zero, and a payment reduces it — so all three actions read against
        // it, and the user shouldn't have to remember the row they tapped.
        val avatar = view.findViewById<TextView>(R.id.tvAvatar)
        val tvBalance = view.findViewById<TextView>(R.id.tvBalance)
        val tvCaption = view.findViewById<TextView>(R.id.tvBalanceCaption)

        val trimmed = account.name.trim()
        avatar.text = if (trimmed.isNotEmpty()) trimmed[0].uppercase() else "?"

        val tile: String
        val ink: String
        when {
            account.dueAmount > 0 -> {
                tile = "#FCEBEB"; ink = "#791F1F"
                tvBalance.text = money(account.dueAmount)
                tvBalance.setTextColor(Color.parseColor("#B23A3A"))
                tvCaption.text = "owes you"
            }
            account.dueAmount < 0 -> {
                tile = "#E1F5EE"; ink = "#0F6E56"
                tvBalance.text = money(-account.dueAmount)
                tvBalance.setTextColor(Color.parseColor("#0F6E56"))
                tvCaption.text = "in advance"
            }
            else -> {
                tile = "#F3ECDD"; ink = "#8A8272"
                tvBalance.text = money(0.0)
                tvBalance.setTextColor(Color.parseColor("#8A8272"))
                tvCaption.text = "settled"
            }
        }
        avatar.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(tile))
        avatar.setTextColor(Color.parseColor(ink))

        // Name the exact sum to be written off. The confirmation that follows
        // says only "Clear all dues", which is easy to accept for the wrong
        // customer.
        //
        // Settling a zero balance is refused, not just discouraged: it would
        // write a WRITE_OFF of 0, which the server rejects — and a rejected
        // transaction holds back every later one for that customer, silently.
        val canSettle = kotlin.math.abs(account.dueAmount) > 0.005
        view.findViewById<TextView>(R.id.tvSettleSub).text =
            if (canSettle) "Clear the full ${money(kotlin.math.abs(account.dueAmount))}"
            else "Nothing to clear"
        btnSettle.alpha = if (canSettle) 1.0f else 0.45f
        btnSettle.isEnabled = canSettle

        // Delete is refused for an unsettled account. Say so here rather than
        // after the tap — the activity's own check stays as the real guard.
        val canDelete = account.dueAmount == 0.0
        view.findViewById<TextView>(R.id.tvDeleteSub).text =
            if (canDelete) "Removes them from your list" else "Settle the balance first"
        btnDelete.alpha = if (canDelete) 1.0f else 0.45f
        btnDelete.isEnabled = canDelete
        view.findViewById<View>(R.id.ivDeleteChevron).visibility =
            if (canDelete) View.VISIBLE else View.INVISIBLE

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

        // Both ids travel. The server id fetches history from the API; the
        // local id finds transactions that haven't synced yet, and is the only
        // one that matches this device's own credit_accounts row.
        intent.putExtra("ACCOUNT_ID", account.serverId ?: -1)
        intent.putExtra("LOCAL_ACCOUNT_ID", account.id)
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

        val owed = account.dueAmount
        view.findViewById<TextView>(R.id.tvOwesCaption).text =
            if (owed < 0) "Currently in advance" else "Currently owes"
        view.findViewById<TextView>(R.id.tvOwes).apply {
            text = money(kotlin.math.abs(owed))
            setTextColor(Color.parseColor(if (owed < 0) "#0F6E56" else "#B23A3A"))
        }

        val tvAfter = view.findViewById<TextView>(R.id.tvBalanceAfter)
        val tvAfterCaption = view.findViewById<TextView>(R.id.tvBalanceAfterCaption)

        // Shows where the balance lands before the user commits. Overpayment is
        // allowed and becomes an advance — correct, but previously invisible
        // until after the payment was recorded.
        fun refreshAfter() {
            val entered = etAmount.text.toString().toDoubleOrNull() ?: 0.0
            val after = owed - entered
            tvAfter.text = money(kotlin.math.abs(after))
            when {
                after > 0 -> {
                    tvAfterCaption.text = "Balance after"
                    tvAfter.setTextColor(Color.parseColor("#B23A3A"))
                }
                after < 0 -> {
                    tvAfterCaption.text = "In advance after"
                    tvAfter.setTextColor(Color.parseColor("#0F6E56"))
                }
                else -> {
                    tvAfterCaption.text = "Settled after"
                    tvAfter.setTextColor(Color.parseColor("#8A8272"))
                }
            }
        }

        // Quick amounts. The full balance is offered because clearing a debt
        // outright is common and typing five digits to do it is friction.
        val chip1 = view.findViewById<TextView>(R.id.chipQuick1)
        val chip2 = view.findViewById<TextView>(R.id.chipQuick2)
        val chipFull = view.findViewById<TextView>(R.id.chipFull)

        fun fill(amount: Double) {
            etAmount.setText(if (amount % 1.0 == 0.0) amount.toLong().toString() else "%.2f".format(amount))
            etAmount.setSelection(etAmount.text?.length ?: 0)
        }

        if (owed > 0) {
            // Round suggestions below the balance; hidden when they'd exceed it
            // or duplicate the full amount.
            val suggestions = listOf(500.0, 1000.0, 2000.0, 5000.0, 10000.0)
                .filter { it < owed }
                .takeLast(2)

            chip1.visibility = if (suggestions.isNotEmpty()) View.VISIBLE else View.GONE
            chip2.visibility = if (suggestions.size > 1) View.VISIBLE else View.GONE
            suggestions.getOrNull(0)?.let { a -> chip1.text = money(a); chip1.setOnClickListener { fill(a) } }
            suggestions.getOrNull(1)?.let { a -> chip2.text = money(a); chip2.setOnClickListener { fill(a) } }

            chipFull.text = "Full ${money(owed)}"
            chipFull.setOnClickListener { fill(owed) }
        } else {
            // Nothing outstanding — any payment here just builds an advance,
            // so there is no "full" amount to offer.
            chip1.visibility = View.GONE
            chip2.visibility = View.GONE
            chipFull.visibility = View.GONE
        }

        etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshAfter()
        })
        refreshAfter()

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        // The card is the layout's own rounded background, so the dialog
        // window behind it must be transparent — otherwise its default white
        // sheet shows through as a square behind the rounded corners.
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

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

                // Applied as an adjustment in SQL rather than a total computed
                // from the captured account, which may be out of date by the
                // time the dialog is confirmed.
                db.creditAccountDao().addToDue(account.id, -amount, shopId)

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

        // The amount is the point of this dialog, so it leads and the button
        // repeats it. "Clear all dues for [name]?" never showed a figure, which
        // is easy to accept for the wrong customer.
        tvTitle.text = account.name
        dialogView.findViewById<TextView>(R.id.tvSettleAmount).text =
            money(kotlin.math.abs(account.dueAmount))
        btnConfirm.text = "Settle ${money(kotlin.math.abs(account.dueAmount))}"

        tvMessage.text =
            if (account.dueAmount < 0)
                "The advance is cleared and the amount is logged in their history."
            else
                "The balance goes to zero and the amount is logged in their history. " +
                "This doesn't record money received — use Record a payment for that."

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // The card is the layout's own rounded background, so the dialog
        // window behind it must be transparent — otherwise its default white
        // sheet shows through as a square behind the rounded corners.
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {

            dialog.dismiss()

            lifecycleScope.launch {

                // Re-read before clearing: the amount written off has to be the
                // balance at this moment, not the one captured when the sheet
                // was opened. Settling to zero is genuinely absolute, so
                // updateDue is right here — only the logged figure needs to be
                // current.
                val cleared = db.creditAccountDao().getById(account.id, shopId)?.dueAmount
                    ?: account.dueAmount

                // Re-checked here, not only on the sheet: a payment or a sync
                // can land between opening it and confirming. Writing a
                // zero-amount adjustment would be rejected by the server and
                // would then block this customer's whole queue.
                if (kotlin.math.abs(cleared) <= 0.005) {
                    Toast.makeText(
                        this@CreditAccountsActivity,
                        "${account.name} is already settled",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadAccounts()
                    return@launch
                }

                db.creditAccountDao().updateDue(account.id, 0.0, shopId)

                // Two different events, recorded as two different types.
                // SETTLE meant both — debt forgiven and money handed back — so
                // every reader had to work out which by replaying the ledger,
                // and a refund ended up counted as if it were a sale.
                //
                //   owes money   → WRITE_OFF, debt forgiven, no cash moved
                //   in credit    → REFUND, the shop hands the money back
                //
                // The amount is always a magnitude; the type carries the
                // direction. Logging 0 (as this once did) left the history
                // reading "₹0" with no trace of the sum involved.
                db.creditTransactionDao().insert(
                    CreditTransaction(
                        accountId = account.id,
                        amount = kotlin.math.abs(cleared),
                        type = if (cleared < 0) "REFUND" else "WRITE_OFF",
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

            // Dismiss the keyboard only. This used to wipe the search text as
            // well, so a stray tap on any empty part of the screen threw away
            // what the user had typed.
            etSearch.clearFocus()

            // Hide keyboard
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)

            false
        }
    }

    private fun money(v: Double): String =
        if (v % 1.0 == 0.0) "₹${v.toLong()}" else "₹${"%.2f".format(v)}"

    private fun updateSummary(accounts: List<CreditAccount>) {

        val totalDue = accounts.filter { it.dueAmount > 0 }.sumOf { it.dueAmount }
        val totalAdvance = accounts.filter { it.dueAmount < 0 }.sumOf { -it.dueAmount }

        val dueCount = accounts.count { it.dueAmount > 0 }
        val advanceCount = accounts.count { it.dueAmount < 0 }
        val settledCount = accounts.count { it.dueAmount == 0.0 }

        val net = totalDue - totalAdvance

        // Net leads, and says which way it points. "₹48,200" alone doesn't tell
        // you whether that's money coming to you or owed out.
        findViewById<TextView>(R.id.tvNetBalance).text = money(kotlin.math.abs(net))
        findViewById<TextView>(R.id.tvNetCaption).apply {
            when {
                net > 0 -> { text = "owed to you"; setTextColor(Color.parseColor("#B23A3A")) }
                net < 0 -> { text = "held in advance"; setTextColor(Color.parseColor("#0F6E56")) }
                else    -> { text = "all settled"; setTextColor(Color.parseColor("#8A8272")) }
            }
        }

        findViewById<TextView>(R.id.tvTotalDue).text = money(totalDue)
        findViewById<TextView>(R.id.tvTotalAdvance).text = money(totalAdvance)

        // Counts live with their labels rather than in a card of their own.
        findViewById<TextView>(R.id.tvDueCount).text = "Due · $dueCount"
        findViewById<TextView>(R.id.tvAdvanceCount).text = "Advance · $advanceCount"

        findViewById<TextView>(R.id.chipAll).text = "All · ${accounts.size}"
        findViewById<TextView>(R.id.chipDue).text = "Due · $dueCount"
        findViewById<TextView>(R.id.chipAdvance).text = "Advance · $advanceCount"
        findViewById<TextView>(R.id.chipSettled).text = "Settled · $settledCount"
    }

    private fun applyFilter(accounts: List<CreditAccount>) {

        val filtered = when (currentFilter) {
            "DUE" -> accounts.filter { it.dueAmount > 0 }
            "ADVANCE" -> accounts.filter { it.dueAmount < 0 }
            "SETTLED" -> accounts.filter { it.dueAmount == 0.0 }
            else -> accounts
        }

        adapter.update(filtered)

        val hasQuery = etSearch.text?.isNotBlank() == true
        tvCustomersEmpty.text = if (hasQuery) "No customers match your search"
            else "No customers yet — tap \"Add customer\" to get started"
        tvCustomersEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        rvCustomers.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Filter chips replace the old "tap a summary card" filtering, which had a
     * dimming effect as its only feedback — you couldn't tell what was
     * filterable or which filter was on. Selection is driven by
     * android:selected, so the chip drawable and text colour follow it.
     */
    private fun setupCardClicks() {

        val chips = mapOf(
            R.id.chipAll to "ALL",
            R.id.chipDue to "DUE",
            R.id.chipAdvance to "ADVANCE",
            R.id.chipSettled to "SETTLED"
        )

        chips.forEach { (id, filter) ->
            findViewById<View>(id).setOnClickListener {
                // Tapping the active chip clears back to All, so there is
                // always a way out without hunting for the All chip.
                currentFilter = if (currentFilter == filter) "ALL" else filter
                syncChipSelection()
                loadAccounts()
            }
        }

        syncChipSelection()
    }

    private fun syncChipSelection() {
        val selectedId = when (currentFilter) {
            "DUE" -> R.id.chipDue
            "ADVANCE" -> R.id.chipAdvance
            "SETTLED" -> R.id.chipSettled
            else -> R.id.chipAll
        }
        listOf(R.id.chipAll, R.id.chipDue, R.id.chipAdvance, R.id.chipSettled)
            .forEach { findViewById<View>(it).isSelected = (it == selectedId) }
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

        // The card is the layout's own rounded background, so the dialog
        // window behind it must be transparent — otherwise its default white
        // sheet shows through as a square behind the rounded corners.
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val message = view.findViewById<TextView>(R.id.tvMessage)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemove)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnOk = view.findViewById<MaterialButton>(R.id.btnOk)

        view.findViewById<TextView>(R.id.tvDeleteName).text = account.name
        message.text = "Their account is removed from your list. " +
            "The balance is already settled, so nothing is owed either way."

        btnOk.visibility = View.GONE
        btnCancel.visibility = View.VISIBLE
        btnCancel.setOnClickListener { dialog.dismiss() }

        // ================= NO INTERNET =================
        // The note appears only here — when Delete is actually disabled. Online
        // users never see it, since the requirement doesn't affect them.
        if (!isInternetAvailable()) {

            view.findViewById<View>(R.id.layoutOfflineNote).visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.tvOfflineNote).text =
                "You're offline. Deleting needs a connection so this customer is " +
                "removed everywhere, not just on this device."

            btnRemove.isEnabled = false
            btnRemove.alpha = 0.45f

        }
        // ================= INTERNET AVAILABLE =================
        else {

            btnRemove.setOnClickListener {

                lifecycleScope.launch(Dispatchers.IO) {

                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)

                    val api = RetrofitClient.api

                    // serverId is nullable, and `serverId != -1` is TRUE when it
                    // is null — so !! threw on any account that had never
                    // synced. The throw was swallowed by the catch below, which
                    // skipped deactivate() with it, and the toast still said
                    // "Account removed". Nothing was removed.
                    val serverId = account.serverId
                    var removed = false

                    try {
                        if (token != null && serverId != null && serverId != -1) {
                            api.deactivateCreditAccount(token, serverId)
                        }

                        db.creditAccountDao().deactivate(account.id, shopId)
                        removed = true

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        loadAccounts()

                        Toast.makeText(
                            this@CreditAccountsActivity,
                            if (removed) "Account removed"
                            else "Couldn't remove ${account.name}. Try again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        dialog.show()
    }
}