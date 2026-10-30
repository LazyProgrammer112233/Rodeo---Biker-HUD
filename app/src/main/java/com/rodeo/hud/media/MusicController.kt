package com.rodeo.hud.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val app: String = "",
    val playing: Boolean = false,
    val art: Bitmap? = null,
    val hasAccess: Boolean = false,
    /** Track length; 0 when the player doesn't say (live radio). */
    val durationMs: Long = 0,
    /** Position at [positionAt] (elapsedRealtime), advancing at [rate] while playing. */
    val positionMs: Long = 0,
    val positionAt: Long = 0,
    val rate: Float = 1f,
    val volume: Int = 0,
    val maxVolume: Int = 15,
) {
    fun positionNow(nowElapsed: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (playing && positionAt > 0) positionMs + ((nowElapsed - positionAt) * rate).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

/**
 * Music controls for whatever app is playing. With notification access we read the session
 * (title, artist, art); without it we still send media keys, which every player honours.
 */
class MusicController(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(context, MediaListenerService::class.java)
    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now.asStateFlow()
    private var controller: MediaController? = null
    private val prefs = context.getSharedPreferences("music", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { attach() }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { attach() }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = publishVolume()
    }
    private var volumeRegistered = false

    fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(context.packageName)
    }

    /**
     * Opens Rodeo's own notification-access switch. Android 13+ greys it out for apps installed
     * from an APK until "Allow restricted settings" is turned on in App info, so the caller also
     * explains that.
     */
    fun accessIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** App info, where "Allow restricted settings" lives (top-right menu). */
    fun appInfoIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun start() {
        if (!volumeRegistered) {
            ContextCompat.registerReceiver(context, volumeReceiver, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            volumeRegistered = true
        }
        if (!hasAccess()) {
            _now.value = NowPlaying(hasAccess = false)
            publishVolume()
            return
        }
        runCatching { msm.addOnActiveSessionsChangedListener(sessionsListener, component) }
        attach()
    }

    fun stop() {
        if (volumeRegistered) {
            runCatching { context.unregisterReceiver(volumeReceiver) }
            volumeRegistered = false
        }
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun attach() {
        val sessions = runCatching { msm.getActiveSessions(component) }.getOrDefault(emptyList())
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback)
            best?.packageName?.let { prefs.edit().putString(KEY_LAST_PLAYER, it).apply() }
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        val appLabel = c?.packageName?.let { pkg ->
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }.orEmpty()
        val ps = c?.playbackState
        _now.value = NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            app = appLabel,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            hasAccess = true,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0,
            positionMs = ps?.position?.takeIf { it >= 0 } ?: 0,
            positionAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            rate = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1),
        )
    }

    private fun publishVolume() {
        _now.value = _now.value.copy(
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1),
        )
    }

    fun volumeUp() = adjust(AudioManager.ADJUST_RAISE)
    fun volumeDown() = adjust(AudioManager.ADJUST_LOWER)

    private fun adjust(direction: Int) {
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        publishVolume()
    }

    fun playPause() {
        val c = controller
        if (c != null) {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        } else {
            startPlayer(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return
        }
        val n = _now.value
        // Freeze or restart the clock at the current position until the player reports back.
        _now.value = n.copy(playing = !n.playing, positionMs = n.positionNow(), positionAt = SystemClock.elapsedRealtime())
    }

    fun play() = controller?.transportControls?.play() ?: startPlayer(KeyEvent.KEYCODE_MEDIA_PLAY)

    /**
     * No player is open: a media key first (most players resume from it). If nothing starts within
     * a moment, open the last music app the rider used (or the phone's music app) and press play.
     */
    private fun startPlayer(code: Int) {
        key(code)
        // Without notification access Rodeo can't see whether the key worked, so it stops there.
        if (!hasAccess()) return
        main.postDelayed({
            if (controller != null) return@postDelayed
            val pm = context.packageManager
            val launch = prefs.getString(KEY_LAST_PLAYER, null)?.let { pm.getLaunchIntentForPackage(it) }
                ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
            runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            main.postDelayed({
                val c = controller
                if (c == null) key(KeyEvent.KEYCODE_MEDIA_PLAY)
                else if (c.playbackState?.state != PlaybackState.STATE_PLAYING) c.transportControls.play()
            }, 3_000)
        }, 1_500)
    }

    fun pause() = controller?.transportControls?.pause() ?: key(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun next() = controller?.transportControls?.skipToNext() ?: key(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = controller?.transportControls?.skipToPrevious() ?: key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private companion object {
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val KEY_LAST_PLAYER = "lastPlayer"
    }
}
