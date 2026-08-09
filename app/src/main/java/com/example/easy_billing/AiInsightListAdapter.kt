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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.AiInsight

private val AI_INSIGHT_ROW_DIFF_CALLBACK = object : DiffUtil.ItemCallback<AiInsightListAdapter.Row>() {
    override fun areItemsTheSame(oldItem: AiInsightListAdapter.Row, newItem: AiInsightListAdapter.Row): Boolean =
        when {
            oldItem is AiInsightListAdapter.Row.Header && newItem is AiInsightListAdapter.Row.Header ->
                oldItem.type == newItem.type
            oldItem is AiInsightListAdapter.Row.Item && newItem is AiInsightListAdapter.Row.Item ->
                // Backend generates a fixed set of insights per report; (type, title) is
                // stable identity within one report even though there's no server id.
                oldItem.insight.type == newItem.insight.type && oldItem.insight.title == newItem.insight.title
            else -> false
        }

    override fun areContentsTheSame(oldItem: AiInsightListAdapter.Row, newItem: AiInsightListAdapter.Row): Boolean =
        oldItem == newItem
}

/**
 * Grouped insights list for the AI screen: severity section headers (fire → leak → gold)
 * with a themed card per insight. Reuses the existing action routing.
 */
class AiInsightListAdapter(
    private val context: Context
) : ListAdapter<AiInsightListAdapter.Row, RecyclerView.ViewHolder>(AI_INSIGHT_ROW_DIFF_CALLBACK) {

    sealed class Row {
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
        "fire" -> TypeStyle(context.getString(R.string.ai_insight_header_fire), "#FBEDED", "#791F1F", "#791F1F", R.drawable.ic_kpi_alert)
        "leak" -> TypeStyle(context.getString(R.string.ai_insight_header_leak), "#FAEEDA", "#8A6526", "#8A6526", R.drawable.ic_trending_down)
        "gold" -> TypeStyle(context.getString(R.string.ai_insight_header_gold), "#DDEEEE", "#0F6E56", "#0F6E56", R.drawable.ic_kpi_badge_check)
        else -> TypeStyle(context.getString(R.string.ai_insight_header_default), "#F1EFE8", "#9A8F79", "#C9C3B4", R.drawable.ic_kpi_badge_check)
    }

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
        submitList(built)
    }

    /** The insight at a row position, or null if that row is a section header. */
    fun insightAt(position: Int): AiInsight? =
        (currentList.getOrNull(position) as? Row.Item)?.insight

    /** True if the row at this position is a section header (not swipe-dismissible). */
    fun isHeader(position: Int): Boolean = currentList.getOrNull(position) is Row.Header

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_ai_insight_header, parent, false))
        } else {
            ItemVH(inflater.inflate(R.layout.item_ai_insight, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
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
        private val accent: View = v.findViewById(R.id.viewInsightAccent)
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
            accent.setBackgroundColor(ink)

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
