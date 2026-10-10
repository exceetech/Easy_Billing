package com.example.easy_billing

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.AppTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/** One scrap entry, from the backend or from this phone. */
data class ScrapRow(
    val name: String,
    val qty: Double,
    val loss: Double,
    val reason: String,   // Expired / Damaged / Spoiled / Other
    val date: String,     // yyyy-MM-dd
    val dateTime: String  // yyyy-MM-dd'T'HH:mm:ss
)

/** Loading and counting for the Profit Analytics > Scrap tab (no UI here). */
object ScrapData {

    class Result(val rows: List<ScrapRow>, val backendOk: Boolean)

    fun nameOf(n: String, v: String?) = if (v.isNullOrBlank()) n else "$n ($v)"

    fun normReason(r: String?): String = when (r?.trim()?.lowercase()) {
        "expired" -> "Expired"
        "damaged" -> "Damaged"
        "spoiled" -> "Spoiled"
        else -> "Other"
    }

    /**
     * Every scrap entry from the backend (GET /scrap/{shop_id}), plus this phone's entries that
     * have not synced yet. If the backend can't be reached, everything on this phone is used
     * and [Result.backendOk] is false. An unsynced local entry that the backend already has
     * (same product, quantity and minute) is not counted twice.
     */
    suspend fun load(ctx: Context): Result {
        val prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
        val token = prefs.getString("TOKEN", null)
        val shopId = prefs.getInt("SHOP_ID", -1)
        val dao = AppDatabase.getDatabase(ctx).scrapDao()

        val rows = ArrayList<ScrapRow>()
        var backendOk = false
        if (!token.isNullOrEmpty() && shopId != -1) {
            try {
                RetrofitClient.api.getScrap(token, shopId).forEach { r ->
                    val dt = r.created_at.replace(' ', 'T')
                    if (dt.length >= 10) rows.add(
                        ScrapRow(nameOf(r.product_name, r.variant_name), r.quantity, r.taxable_amount,
                            normReason(r.reason), dt.substring(0, 10), dt.take(19))
                    )
                }
                backendOk = true
            } catch (_: Exception) { /* offline -> this phone's entries below */ }
        }
        val local = withContext(Dispatchers.IO) {
            if (backendOk) dao.getUnsynced() else dao.getRecent(100000)
        }
        val seen = rows.map { Triple(it.name, it.qty, it.dateTime.take(16)) }.toHashSet()
        val isoD = AppTime.isoDate()
        val isoDT = AppTime.isoDateTime()
        local.forEach { s ->
            val d = Date(s.createdAt)
            val row = ScrapRow(nameOf(s.productName, s.variantName), s.quantity, s.taxableAmount,
                normReason(s.reason), isoD.format(d), isoDT.format(d))
            if (!backendOk || Triple(row.name, row.qty, row.dateTime.take(16)) !in seen) rows.add(row)
        }
        return Result(rows, backendOk)
    }

    /**
     * "Times scrapped": one scrap action per product. Scrapping one product across several
     * batches saves one entry per batch, all within a moment of each other, so entries of the
     * same product less than a minute apart count as ONE time.
     */
    fun events(rows: List<ScrapRow>): Int {
        val fmt = AppTime.isoDateTime()
        return rows.groupBy { it.name }.values.sumOf { list ->
            val times = list.map { runCatching { fmt.parse(it.dateTime.take(19))!!.time }.getOrDefault(0L) }.sorted()
            var n = 0
            var last = Long.MIN_VALUE
            times.forEach { ms ->
                if (last == Long.MIN_VALUE || ms - last > 60_000L) n++
                last = ms
            }
            n
        }
    }
}
