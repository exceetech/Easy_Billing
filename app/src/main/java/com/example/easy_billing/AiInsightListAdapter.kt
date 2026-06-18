package com.example.easy_billing

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.AiInsight

/**
 * Grouped insights list for the AI screen: severity section headers (fire → leak → gold)
 * with a themed card per insight. Reuses the existing action routing.
 */
class AiInsightListAdapter(
    private val context: Context
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val type: String) : Row()
        data class Item(val insight: AiInsight) : Row()
    }

    private data class TypeStyle(
        val label: String,
        val square: String,
        val ink: String,
        val dot: String,
        val icon: Int
    )

    private fun styleFor(type: String): TypeStyle = when (type.lowercase()) {
        "fire" -> TypeStyle("NEEDS ATTENTION", "#FCEBEB", "#A32D2D", "#A32D2D", R.drawable.ic_kpi_alert)
        "leak" -> TypeStyle("PLUGGING LEAKS", "#FAEEDA", "#854F0B", "#BA7517", R.drawable.ic_trending_down)
        "gold" -> TypeStyle("WHAT'S WORKING", "#EAF3DE", "#0F6E56", "#0F6E56", R.drawable.ic_kpi_badge_check)
        else -> TypeStyle("INSIGHTS", "#F1EFE8", "#5F5E5A", "#888780", R.drawable.ic_kpi_badge_check)
    }

    private var rows: List<Row> = emptyList()

    fun submit(insights: List<AiInsight>) {
        // Backend already orders fire → leak → gold; insert a header when the type changes.
        val built = mutableListOf<Row>()
        var lastType: String? = null
        for (ins in insights) {
            val t = ins.type.lowercase()
            if (t != lastType) {
                built.add(Row.Header(t))
                lastType = t
            }
            built.add(Row.Item(ins))
        }
        rows = built
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_ai_insight_header, parent, false))
        } else {
            ItemVH(inflater.inflate(R.layout.item_ai_insight, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(styleFor(row.type))
            is Row.Item -> (holder as ItemVH).bind(row.insight)
        }
    }

    private inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val dot: View = v.findViewById(R.id.viewHeaderDot)
        private val label: TextView = v.findViewById(R.id.tvHeaderLabel)
        fun bind(style: TypeStyle) {
            label.text = style.label
            dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor(style.dot))
        }
    }

    private inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        private val icon: ImageView = v.findViewById(R.id.ivInsightIcon)
        private val title: TextView = v.findViewById(R.id.tvInsightTitle)
        private val desc: TextView = v.findViewById(R.id.tvInsightDescription)
        private val actionRow: View = v.findViewById(R.id.rowInsightAction)
        private val actionText: TextView = v.findViewById(R.id.tvInsightAction)
        private val actionArrow: ImageView = v.findViewById(R.id.ivInsightArrow)

        fun bind(insight: AiInsight) {
            val style = styleFor(insight.type)
            val ink = Color.parseColor(style.ink)

            title.text = insight.title
            desc.text = insight.description
            icon.setImageResource(style.icon)
            icon.setColorFilter(ink)
            icon.backgroundTintList = ColorStateList.valueOf(Color.parseColor(style.square))

            val hasAction = !insight.actionText.isNullOrEmpty() &&
                !insight.actionType.isNullOrEmpty() && insight.actionType != "NONE"
            if (hasAction) {
                actionRow.visibility = View.VISIBLE
                actionText.text = insight.actionText
                actionText.setTextColor(ink)
                actionArrow.setColorFilter(ink)
                itemView.isClickable = true
                itemView.setOnClickListener { handleAction(insight.actionType!!) }
            } else {
                actionRow.visibility = View.GONE
                itemView.isClickable = false
                itemView.setOnClickListener(null)
            }
        }
    }

    private fun handleAction(actionType: String) {
        val target = when (actionType) {
            "VIEW_INVENTORY", "VIEW_DEAD_STOCK", "VIEW_SCRAP" -> InventoryActivity::class.java
            "VIEW_CREDIT" -> CreditAccountsActivity::class.java
            "VIEW_BILLS" -> BillHistoryActivity::class.java
            "VIEW_PURCHASES" -> PurchaseHistoryActivity::class.java
            "VIEW_RETURNS" -> PurchaseReturnActivity::class.java
            else -> null
        }
        target?.let { context.startActivity(Intent(context, it)) }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
