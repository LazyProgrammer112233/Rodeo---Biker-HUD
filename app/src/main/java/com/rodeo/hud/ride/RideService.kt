package com.rodeo.hud.ride

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rodeo.hud.MainActivity
import com.rodeo.hud.R
import com.rodeo.hud.RodeoApp
import com.rodeo.hud.graph
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/** Keeps ride tracking alive with the screen off or the app in the background. */
class RideService : LifecycleService() {

    private var holding = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val g = graph
        // With the microphone type too, the wake phrase and voice SOS keep working with the
        // screen off or another app in front.
        val mic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIF_ID, build("Ride starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0),
            )
        }.recoverCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, build("Ride starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }
        if (started.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!holding) {
            holding = true
            g.location.acquire()
            g.guardian.start()
        }
        g.ride.start()
        lifecycleScope.launch {
            g.ride.live.collectLatest { l ->
                if (!l.active) return@collectLatest
                val text = String.format(Locale.US, "%.1f km · %d km/h · %s", l.distanceM / 1000, l.speedKmh.toInt(), formatDuration(l.elapsedMs))
                ContextCompat.getSystemService(this@RideService, android.app.NotificationManager::class.java)
                    ?.notify(NOTIF_ID, build(text))
            }
        }
        return START_STICKY
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, RodeoApp.CH_RIDE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Rodeo · ride in progress")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        if (holding) {
            graph.location.release()
            holding = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val NOTIF_ID = 42

        fun canRun(context: Context) =
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            if (canRun(context)) ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
            else context.graph.ride.start()
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RideService::class.java))
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) String.format(Locale.US, "%d:%02d h", h, m) else String.format(Locale.US, "%d:%02d", m, s % 60)
}
