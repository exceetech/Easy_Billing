package com.example.easy_billing.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.BaseActivity
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.ImportService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImportServicesActivity : BaseActivity() {

    private lateinit var rvImportServices: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: ImportServiceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_services)

        rvImportServices = findViewById(R.id.rvImportServices)
        tvEmpty = findViewById(R.id.tvEmpty)

        findViewById<View>(R.id.toolbar).setOnClickListener { finish() }

        adapter = ImportServiceAdapter(emptyList())
        rvImportServices.layoutManager = LinearLayoutManager(this)
        rvImportServices.adapter = adapter

        findViewById<View>(R.id.fabAdd).setOnClickListener {
            startActivity(Intent(this, AddImportServiceActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        val dao = AppDatabase.getDatabase(this).importServiceDao()
        dao.getAllImportServicesLive().observe(this) { list ->
            adapter.updateData(list)
            tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}

class ImportServiceAdapter(private var items: List<ImportService>) : RecyclerView.Adapter<ImportServiceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvInvoiceNumber: TextView = view.findViewById(R.id.tvInvoiceNumber)
        val tvInvoiceDate: TextView = view.findViewById(R.id.tvInvoiceDate)
        val tvInvoiceValue: TextView = view.findViewById(R.id.tvInvoiceValue)
        val tvPlaceOfSupply: TextView = view.findViewById(R.id.tvPlaceOfSupply)
        val tvTaxDetails: TextView = view.findViewById(R.id.tvTaxDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_import_service, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvInvoiceNumber.text = item.invoiceNumber
        
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        holder.tvInvoiceDate.text = sdf.format(Date(item.invoiceDate))
        
        holder.tvInvoiceValue.text = item.invoiceValue.toString()
        holder.tvPlaceOfSupply.text = item.placeOfSupply
        holder.tvTaxDetails.text = "IGST: ₹${item.igstPaid} | Cess: ₹${item.cessPaid}"
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<ImportService>) {
        items = newItems
        notifyDataSetChanged()
    }
}
