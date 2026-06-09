package com.example.easy_billing

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.AiInsight
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class AiInsightsAdapter(
    private val context: Context,
    private var insights: List<AiInsight>,
    private val onMinimize: () -> Unit
) : RecyclerView.Adapter<AiInsightsAdapter.InsightViewHolder>() {

    fun updateData(newInsights: List<AiInsight>) {
        insights = newInsights
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InsightViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_insight_card, parent, false)
        return InsightViewHolder(view)
    }

    override fun onBindViewHolder(holder: InsightViewHolder, position: Int) {
        val insight = insights[position]
        holder.bind(insight)
    }

    override fun getItemCount(): Int = insights.size

    inner class InsightViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardBase: MaterialCardView = itemView.findViewById(R.id.cardInsightBase)
        private val tvIcon: TextView = itemView.findViewById(R.id.tvInsightIcon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvInsightTitle)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvInsightDescription)
        private val btnAction: MaterialButton = itemView.findViewById(R.id.btnInsightAction)
        private val btnMinimize: TextView = itemView.findViewById(R.id.btnMinimizeInsight)

        fun bind(insight: AiInsight) {
            tvTitle.text = insight.title
            tvDescription.text = insight.description

            // Styling based on type
            when (insight.type.lowercase()) {
                "fire" -> {
                    tvIcon.text = "🔥"
                    cardBase.setCardBackgroundColor(Color.parseColor("#FEF2F2")) // Light red
                    cardBase.strokeColor = Color.parseColor("#FECACA")
                    tvTitle.setTextColor(Color.parseColor("#991B1B"))
                }
                "leak" -> {
                    tvIcon.text = "⚠️"
                    cardBase.setCardBackgroundColor(Color.parseColor("#FFFBEB")) // Light orange/yellow
                    cardBase.strokeColor = Color.parseColor("#FDE68A")
                    tvTitle.setTextColor(Color.parseColor("#92400E"))
                }
                "gold" -> {
                    tvIcon.text = "✨"
                    cardBase.setCardBackgroundColor(Color.parseColor("#F0FDF4")) // Light green
                    cardBase.strokeColor = Color.parseColor("#BBF7D0")
                    tvTitle.setTextColor(Color.parseColor("#166534"))
                }
                else -> {
                    tvIcon.text = "💡"
                    cardBase.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                    cardBase.strokeColor = Color.parseColor("#E4E4E7")
                    tvTitle.setTextColor(Color.parseColor("#18181B"))
                }
            }

            // Action Button
            if (!insight.actionText.isNullOrEmpty() && !insight.actionType.isNullOrEmpty() && insight.actionType != "NONE") {
                btnAction.visibility = View.VISIBLE
                btnAction.text = insight.actionText
                btnAction.setOnClickListener {
                    handleAction(insight.actionType)
                }
            } else {
                btnAction.visibility = View.GONE
            }

            // Minimize Button
            btnMinimize.setOnClickListener {
                onMinimize()
            }
        }

        private fun handleAction(actionType: String) {
            when (actionType) {
                "VIEW_INVENTORY" -> {
                    context.startActivity(Intent(context, InventoryActivity::class.java))
                }
                "VIEW_CREDIT" -> {
                    context.startActivity(Intent(context, CreditAccountsActivity::class.java))
                }
                "VIEW_BILLS" -> {
                    context.startActivity(Intent(context, BillHistoryActivity::class.java))
                }
                "VIEW_DEAD_STOCK" -> {
                    // Navigate to inventory, maybe pass a flag for dead stock filtering
                    context.startActivity(Intent(context, InventoryActivity::class.java))
                }
                "VIEW_DASHBOARD" -> {
                    // Already here
                }
                "VIEW_PURCHASES" -> {
                    context.startActivity(Intent(context, PurchaseHistoryActivity::class.java))
                }
                "VIEW_RETURNS" -> {
                    context.startActivity(Intent(context, PurchaseReturnActivity::class.java))
                }
                "VIEW_SCRAP" -> {
                    context.startActivity(Intent(context, InventoryActivity::class.java))
                }
                else -> {}
            }
        }
    }
}
