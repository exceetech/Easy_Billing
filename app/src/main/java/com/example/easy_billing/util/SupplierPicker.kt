package com.example.easy_billing.util

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.SupplierAdapter
import com.example.easy_billing.db.Supplier
import com.example.easy_billing.repository.SupplierRepository
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * Sheet for choosing — or adding — the supplier on a purchase.
 *
 * This is the *only* way the supplier name is set: tapping the field opens
 * this sheet rather than the keyboard. Free-typing a name would let two
 * spellings of one supplier drift apart, and would leave the GSTIN and
 * state beside it describing somebody else.
 *
 * A sheet rather than a dropdown because rows must show name, GSTIN and
 * state together — a name alone can't distinguish two branches of the same
 * trade name, and picking the wrong one puts the wrong state on the
 * invoice, which flips CGST+SGST to IGST.
 */
object SupplierPicker {

    fun show(
        activity: AppCompatActivity,
        onPicked: (Supplier) -> Unit
    ) {
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.dialog_supplier_picker, null)

        val etSearch = view.findViewById<EditText>(R.id.etSearchSupplier)
        val rv = view.findViewById<RecyclerView>(R.id.rvSuppliers)
        val tvCount = view.findViewById<TextView>(R.id.tvSupplierCount)
        val empty = view.findViewById<View>(R.id.emptySuppliers)
        val btnNew = view.findViewById<MaterialButton>(R.id.btnNewSupplier)

        rv.layoutManager = LinearLayoutManager(activity)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var runnable: Runnable? = null

        activity.lifecycleScope.launch {
            val all = SupplierRepository.all(activity)

            val adapter = SupplierAdapter(all.toMutableList()) { supplier ->
                dialog.dismiss()
                onPicked(supplier)
            }
            rv.adapter = adapter
            tvCount.text = if (all.size == 1) "1 supplier" else "${all.size} suppliers"
            empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE

            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    runnable?.let { handler.removeCallbacks(it) }
                    runnable = Runnable {
                        val q = s?.toString()?.trim()?.take(50).orEmpty()
                        val result = if (q.isEmpty()) all else all.filter {
                            it.name.contains(q, true) ||
                                it.state.contains(q, true) ||
                                (it.gstin?.contains(q, true) == true)
                        }
                        adapter.update(result)
                        empty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
                    }
                    handler.postDelayed(runnable!!, 250)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        btnNew.setOnClickListener {
            dialog.dismiss()
            // Whatever they'd typed into the search box is almost always the
            // supplier they were looking for — carry it into the form.
            showAddSupplierDialog(
                activity,
                prefillName = etSearch.text?.toString()?.trim().orEmpty(),
                onCreated = onPicked
            )
        }

        dialog.setContentView(view)
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        dialog.show()
    }

    /**
     * "New supplier" form. State is derived from the GSTIN whenever one is
     * entered, and only becomes tappable for unregistered suppliers — the
     * two can't be allowed to disagree.
     */
    fun showAddSupplierDialog(
        activity: AppCompatActivity,
        prefillName: String = "",
        onCreated: (Supplier) -> Unit
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_add_supplier, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etSupName)
        val etGstin = view.findViewById<TextInputEditText>(R.id.etSupGstin)
        val tvState = view.findViewById<TextView>(R.id.etSupState)
        val tvStateHint = view.findViewById<TextView>(R.id.tvSupStateHint)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnSupCancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSupSave)

        if (prefillName.isNotEmpty()) etName.setText(prefillName)

        val dialog = AlertDialog.Builder(activity).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val states = GstEngine.INDIA_STATES.values.toList()

        /** State is only the user's to choose while there's no GSTIN. */
        fun refreshStateSource() {
            val g = etGstin.text?.toString()?.trim()?.uppercase().orEmpty()
            val fromGstin = if (g.length >= 2) GstEngine.INDIA_STATES[g.substring(0, 2)] else null
            if (fromGstin != null) {
                tvState.text = fromGstin
                tvState.isEnabled = false
                tvStateHint.visibility = View.VISIBLE
            } else {
                tvState.isEnabled = true
                tvStateHint.visibility = View.GONE
            }
        }

        etGstin.addTextChangedListener { refreshStateSource() }
        refreshStateSource()

        tvState.setOnClickListener {
            if (!tvState.isEnabled) return@setOnClickListener
            AlertDialog.Builder(activity)
                .setTitle("Select state")
                .setItems(states.toTypedArray()) { _, which -> tvState.text = states[which] }
                .show()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            btnSave.isEnabled = false
            activity.lifecycleScope.launch {
                val result = SupplierRepository.create(
                    context = activity,
                    name = etName.text?.toString().orEmpty(),
                    gstin = etGstin.text?.toString(),
                    state = tvState.text?.toString().orEmpty()
                )
                when (result) {
                    is SupplierRepository.CreateResult.Ok -> {
                        dialog.dismiss()
                        onCreated(result.supplier)
                    }
                    is SupplierRepository.CreateResult.Duplicate -> {
                        // Already on file — hand them that row rather than
                        // leaving them stuck on a form they can't submit.
                        dialog.dismiss()
                        Toast.makeText(
                            activity,
                            "${result.existing.name} is already saved — selected it",
                            Toast.LENGTH_LONG
                        ).show()
                        onCreated(result.existing)
                    }
                    SupplierRepository.CreateResult.BlankName -> {
                        etName.error = "Enter the supplier name"
                        btnSave.isEnabled = true
                    }
                    SupplierRepository.CreateResult.BadGstin -> {
                        etGstin.error = "Invalid GSTIN"
                        btnSave.isEnabled = true
                    }
                    SupplierRepository.CreateResult.NoState -> {
                        Toast.makeText(activity, "Select the supplier's state", Toast.LENGTH_SHORT).show()
                        btnSave.isEnabled = true
                    }
                }
            }
        }

        dialog.show()
    }
}
