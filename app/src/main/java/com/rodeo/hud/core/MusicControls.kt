package com.rodeo.hud.core

import com.rodeo.hud.media.MusicController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a music control does. */
enum class MusicAction { PREVIOUS, PLAY_PAUSE, NEXT }

/** Where a music control came from, for the on-screen confirmation. */
enum class ControlSource { TAP, KEY, WAVE, VOICE }

/** A music control the rider gave without touching the screen, shown as a big confirmation. */
data class ControlEvent(val action: MusicAction?, val source: ControlSource, val detail: String, val at: Long)

/**
 * One place for hands-off music control: screen taps on the music pad, the volume keys and waves over the
 * phone all end up here, so they share the confirmation and can't double up.
 */
class MusicControls(private val music: MusicController, private val blocked: () -> Boolean) {
    private val _last = MutableStateFlow<ControlEvent?>(null)
    val last: StateFlow<ControlEvent?> = _last.asStateFlow()
    private var lastAt = 0L

    /** Acts on [action]; false when an SOS is on screen or the same control just fired. */
    fun act(action: MusicAction, source: ControlSource, detail: String): Boolean {
        if (blocked()) return false
        val now = System.currentTimeMillis()
        if (now - lastAt < 350) return false
        lastAt = now
        when (action) {
            MusicAction.PREVIOUS -> music.previous()
            MusicAction.PLAY_PAUSE -> music.playPause()
            MusicAction.NEXT -> music.next()
        }
        _last.value = ControlEvent(action, source, detail, now)
        return true
    }

    /** A nudge when the rider nearly did something ("double-tap the right end for next song"). */
    fun hint(source: ControlSource, text: String) {
        _last.value = ControlEvent(null, source, text, System.currentTimeMillis())
    }

    companion object {
        fun label(a: MusicAction) = when (a) {
            MusicAction.PREVIOUS -> "Previous song"
            MusicAction.PLAY_PAUSE -> "Play / pause"
            MusicAction.NEXT -> "Next song"
        }
    }
}
