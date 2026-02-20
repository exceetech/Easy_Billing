import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.db.Bill

class PreviousBillsAdapter(
    private val onBillClick: (Bill) -> Unit
) : RecyclerView.Adapter<PreviousBillsAdapter.ViewHolder>() {

    private var bills = listOf<Bill>()

    fun submitList(list: List<Bill>) {
        bills = list
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvBillInfo: TextView = view.findViewById(R.id.tvBillInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_previous_bill, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = bills.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val bill = bills[position]

        holder.tvBillInfo.text =
            "Invoice #${bill.billNumber}\n" +
                    "Total: ₹%.2f".format(bill.total)

        holder.itemView.setOnClickListener {
            onBillClick(bill)
        }
    }
}