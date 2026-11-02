package com.rodeo.hud.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.rodeo.hud.graph
import com.rodeo.hud.nav.gmaps.GmapsBridge
import com.rodeo.hud.nav.gmaps.MapsNoticeReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Notification access does two jobs for Rodeo:
 *  - it lets Rodeo read and control the active media session (Spotify, YouTube Music, JioSaavn...);
 *  - it lets Rodeo read Google Maps' turn-by-turn notification and put the next turn on the HUD.
 * No other app's notifications are read; nothing leaves the phone.
 */
class MediaListenerService : NotificationListenerService() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val work = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reader by lazy { MapsNoticeReader(applicationContext) }
    /** The key of the navigation notification on screen now. */
    private var navKey: String? = null

    override fun onListenerConnected() {
        val maps = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { it.packageName in sources }
        val nav = maps.firstOrNull { reader.isNavigation(it) }
        if (nav != null) handle(nav) else if (navKey == null) scope.launch { graph.gmaps.onGone() }
    }

    override fun onListenerDisconnected() {
        // Access was switched off or the system unbound us: nothing more will arrive.
        navKey = null
        scope.launch { graph.gmaps.onGone() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in sources) return
        if (reader.isNavigation(sbn)) handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in sources) return
        if (sbn.key == navKey) {
            navKey = null
            scope.launch { graph.gmaps.onGone() }
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        navKey = sbn.key
        scope.launch {
            val read = runCatching { withContext(work) { reader.read(sbn) } }.getOrNull() ?: return@launch
            // A removal may have come in while the picture was being read.
            if (navKey != sbn.key) return@launch
            graph.gmaps.onNotice(read.notice, read.picture, read.arrow)
        }
    }

    companion object {
        /** Apps whose navigation notification is read. The on-device test adds Rodeo itself. */
        @Volatile var sources: Set<String> = setOf(GmapsBridge.GOOGLE_MAPS)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
