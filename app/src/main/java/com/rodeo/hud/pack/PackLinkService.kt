package com.rodeo.hud.pack

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the pack alive with the screen off or another app in front: location keeps flowing, the
 * short-range link keeps scanning, and the voice channel keeps the microphone.
 */
class PackLinkService : LifecycleService() {
    private var holding = false
    private var withMic = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val g = graph
        withMic = g.pack.voice.ui.value.wanted && granted(Manifest.permission.RECORD_AUDIO)
        // startForegroundService() must always be answered with startForeground(), even when stopping.
        val foreground = goForeground(text(g.pack.state.value))
        if (!foreground || !g.pack.state.value.active) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!holding) {
            holding = true
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) g.location.acquire() else holding = false
            lifecycleScope.launch {
                g.pack.state.collectLatest { s ->
                    if (!s.active) {
                        stopSelf()
                        return@collectLatest
                    }
                    getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, build(text(s)))
                }
            }
            // Joining voice later needs the microphone type added to the running service.
            lifecycleScope.launch {
                g.pack.voice.ui.map { it.wanted }.distinctUntilChanged().collect { wanted ->
                    val mic = wanted && granted(Manifest.permission.RECORD_AUDIO)
                    if (mic != withMic) {
                        withMic = mic
                        goForeground(text(g.pack.state.value))
                    }
                }
            }
        }
        // Not sticky: after the process dies the app restores the pack itself when opened.
        return START_NOT_STICKY
    }

    private fun goForeground(text: String): Boolean {
        val location = if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val mic = if (withMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        return runCatching { ServiceCompat.startForeground(this, NOTIF_ID, build(text), location or device or mic) }
            .recoverCatching { ServiceCompat.startForeground(this, NOTIF_ID, build(text), location or device) }
            .recoverCatching { ServiceCompat.startForeground(this, NOTIF_ID, build(text), device) }
            .isSuccess
    }

    private fun text(s: PackState): String {
        val n = s.members.count { !it.isMe }
        val riders = if (n == 1) "1 rider" else "$n riders"
        val voice = if (s.voice.live) " · voice on" else ""
        return "Pack ${s.code.orEmpty()} · $riders$voice"
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, RodeoApp.CH_PACK)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Rodeo · pack mode")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        if (holding) {
            holding = false
            graph.location.release()
        }
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 43

        /** Call from the screen (the app must be in front to start a foreground service). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PackLinkService::class.java)) }
        }
    }
}
