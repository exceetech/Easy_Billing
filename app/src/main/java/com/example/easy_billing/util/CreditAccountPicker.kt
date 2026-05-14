package com.example.easy_billing.util

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.CreditAdapter
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.CreditAccount
import com.example.easy_billing.network.CreateCreditAccountRequest
import com.example.easy_billing.network.RetrofitClient
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reusable helper for picking or creating a Credit Account.
 * Extracted from InvoiceActivity to be used in PurchaseActivity and others.
 */
object CreditAccountPicker {

    fun show(
        activity: AppCompatActivity,
        onAccountSelected: (CreditAccount) -> Unit
    ) {
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.dialog_customer_picker, null)

        val etSearch = view.findViewById<EditText>(R.id.etSearchCustomer)
        val rvCustomers = view.findViewById<RecyclerView>(R.id.rvCustomers)
        val btnNew = view.findViewById<MaterialButton>(R.id.btnNewCustomer)

        rvCustomers.layoutManager = LinearLayoutManager(activity)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var runnable: Runnable? = null

        activity.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(activity)
            val shopId = activity.getSharedPreferences("auth", Context.MODE_PRIVATE).getInt("SHOP_ID", 1)
            val allAccounts = withContext(Dispatchers.IO) {
                db.creditAccountDao().getAll(shopId)
            }
            var currentList = allAccounts.toMutableList()

            val adapter = CreditAdapter(currentList) { account ->
                dialog.dismiss()
                onAccountSelected(account)
            }
            rvCustomers.adapter = adapter

            fun updateList(data: List<CreditAccount>) {
                currentList.clear()
                currentList.addAll(data)
                adapter.notifyDataSetChanged()
            }

            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    runnable?.let { handler.removeCallbacks(it) }
                    runnable = Runnable {
                        val query = s?.toString()?.trim()?.take(50) ?: ""
                        val result = if (query.isEmpty()) allAccounts else allAccounts.filter {
                            it.name.contains(query, true) || it.phone.contains(query)
                        }
                        updateList(result)
                    }
                    handler.postDelayed(runnable!!, 300)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        btnNew.setOnClickListener {
            dialog.dismiss()
            showAddAccountDialog(activity, onAccountSelected)
        }
        
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showAddAccountDialog(
        activity: AppCompatActivity,
        onAccountSelected: (CreditAccount) -> Unit
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_add_customer, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etPhone = view.findViewById<EditText>(R.id.etPhone)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val phone = etPhone.text.toString().trim()
            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(activity, "Enter all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            activity.lifecycleScope.launch {
                val db = AppDatabase.getDatabase(activity)
                val shopId = activity.getSharedPreferences("auth", Context.MODE_PRIVATE).getInt("SHOP_ID", 1)
                
                val existing = withContext(Dispatchers.IO) {
                    db.creditAccountDao().getByPhone(phone, shopId)
                }
                
                if (existing != null) {
                    Toast.makeText(activity, "Account already exists", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val token = activity.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null)
                val newAccount: CreditAccount
                
                if (token == null) {
                    newAccount = CreditAccount(name = name, phone = phone, isSynced = false, shopId = shopId)
                    val id = withContext(Dispatchers.IO) { db.creditAccountDao().insert(newAccount) }
                    onAccountSelected(newAccount.copy(id = id.toInt()))
                } else {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            RetrofitClient.api.createCreditAccount(
                                "Bearer $token", CreateCreditAccountRequest(name, phone)
                            )
                        }
                        newAccount = CreditAccount(
                            name = response.name, phone = response.phone,
                            dueAmount = response.due_amount, serverId = response.id,
                            isSynced = true, shopId = shopId
                        )
                        val id = withContext(Dispatchers.IO) { db.creditAccountDao().insert(newAccount) }
                        onAccountSelected(newAccount.copy(id = id.toInt()))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        val offlineAccount = CreditAccount(name = name, phone = phone, isSynced = false, shopId = shopId)
                        val id = withContext(Dispatchers.IO) { db.creditAccountDao().insert(offlineAccount) }
                        onAccountSelected(offlineAccount.copy(id = id.toInt()))
                    }
                }
                
                Toast.makeText(activity, "Account added", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
