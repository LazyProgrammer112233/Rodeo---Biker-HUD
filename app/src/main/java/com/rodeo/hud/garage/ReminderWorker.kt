package com.rodeo.hud.garage

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rodeo.hud.MainActivity
import com.rodeo.hud.R
import com.rodeo.hud.RodeoApp
import com.rodeo.hud.data.DocType
import com.rodeo.hud.graph
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** Daily check: documents expiring in 15, 7 or 1 day(s), and service due within 200 km. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        g.rides.refresh()
        val garage = g.garage.garage.value
        val today = LocalDate.now()
        val prefs = applicationContext.getSharedPreferences("rodeo_reminders", Context.MODE_PRIVATE)
        garage.documents.forEach { d ->
            val due = d.due ?: return@forEach
            val days = ChronoUnit.DAYS.between(today, due)
            if (days in REMIND_DAYS) {
                val key = "${d.type}-${d.dueEpochDay}-$days"
                if (!prefs.getBoolean(key, false)) {
                    val what = if (d.type == DocType.SERVICE) "Service due" else "${d.type.label} expires"
                    notify(d.type.ordinal, "$what in $days day${if (days == 1L) "" else "s"}", "Due ${due}. Open Rodeo Garage to renew or update.")
                    prefs.edit().putBoolean(key, true).apply()
                }
            }
        }
        val serviceKm = garage.nextServiceKm
        if (serviceKm != null) {
            val odo = g.garage.odometerKm(g.rides.totalKm())
            val left = serviceKm - odo
            val key = "service-km-$serviceKm"
            if (left in 0..200 && !prefs.getBoolean(key, false)) {
                notify(100, "Service due in $left km", "Odometer $odo km, service at $serviceKm km.")
                prefs.edit().putBoolean(key, true).apply()
            }
        }
        return Result.success()
    }

    private fun notify(id: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            applicationContext, id, Intent(applicationContext, MainActivity::class.java).putExtra("open", "garage"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(applicationContext, RodeoApp.CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(1000 + id, n)
    }

    companion object {
        private val REMIND_DAYS = setOf(15L, 7L, 1L)

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("garage-reminders", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Runs the check now (used after the rider edits a date). */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(androidx.work.OneTimeWorkRequestBuilder<ReminderWorker>().build())
        }
    }
}
