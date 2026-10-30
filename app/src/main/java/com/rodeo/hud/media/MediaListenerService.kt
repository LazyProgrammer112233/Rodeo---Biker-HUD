package com.rodeo.hud.media

import android.service.notification.NotificationListenerService

/**
 * Exists only so the user can grant notification access; with it, Rodeo can read and control
 * the active media session (Spotify, YouTube Music, JioSaavn...).
 */
class MediaListenerService : NotificationListenerService()
