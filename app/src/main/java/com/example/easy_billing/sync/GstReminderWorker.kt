package com.example.easy_billing.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.easy_billing.GstReportsActivity
import com.example.easy_billing.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Nudges the owner a few days before the GST due dates:
 *  - monthly filers:   GSTR-1 on the 11th, GSTR-3B on the 20th
 *  - quarterly (QRMP): GSTR-1 on the 13th, GSTR-3B on the 22nd (Jan, Apr, Jul, Oct only)
 *
 * Only runs for shops that have opened the GST report page at least once
 * (gst_prefs "opened") and have not switched reminders off ("reminders_on").
 * Each due date is notified once (stored in gst_prefs "last_g1" / "last_g3b").
 */
class GstReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val gp = ctx.getSharedPreferences("gst_prefs", Context.MODE_PRIVATE)
        if (!gp.getBoolean("opened", false) || !gp.getBoolean("reminders_on", true)) return Result.success()

        val cal = com.example.easy_billing.util.AppTime.calendar()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (hour < 9 || hour >= 20) return Result.success()        // never wake the owner at night

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        if (gp.getBoolean("composition", false)) return Result.success()   // composition shops file neither return
        val quarterly = gp.getBoolean("quarterly", false)
        val month0 = cal.get(Calendar.MONTH)
        if (quarterly && month0 % 3 != 0) return Result.success()   // Jan, Apr, Jul, Oct only

        maybeNotify(ctx, gp, cal, quarterly, "g1", if (quarterly) 13 else 11, R.string.gst_reminder_g1, 7101)
        maybeNotify(ctx, gp, cal, quarterly, "g3b", if (quarterly) 22 else 20, R.string.gst_reminder_g3b, 7102)
        return Result.success()
    }

    private fun maybeNotify(
        ctx: Context, gp: android.content.SharedPreferences, cal: Calendar, quarterly: Boolean,
        kind: String, dueDay: Int, textRes: Int, notifId: Int
    ) {
        val left = dueDay - cal.get(Calendar.DAY_OF_MONTH)
        if (left !in 0..3) return
        // The owner marked this period as filed on the GST page: no more nudges for it.
        val (dueFy, duePeriod) = com.example.easy_billing.gstr1.GstPeriod.dueFor(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), quarterly
        )
        if (gp.getBoolean(com.example.easy_billing.gstr1.GstPeriod.filedKey(dueFy, duePeriod), false)) return
        val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-$kind"
        if (gp.getString("last_$kind", "") == key) return

        val due = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, dueDay) }
        val dueText = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(due.time)

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("gst_reminders", ctx.getString(R.string.gst_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, notifId, Intent(ctx, GstReportsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = ctx.getString(textRes, dueText)
        val n = NotificationCompat.Builder(ctx, "gst_reminders")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ctx.getString(R.string.gst_reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(notifId, n)
        gp.edit().putString("last_$kind", key).apply()
    }
}
