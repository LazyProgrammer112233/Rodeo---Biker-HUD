package com.rodeo.hud.nav.gmaps

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/** One Google Maps notification, read: its text, its arrow picture and what the picture shows. */
class MapsRead(val notice: MapsNotice, val picture: Bitmap?, val arrow: ArrowReading?)

/**
 * Pulls the text and the arrow picture out of Google Maps' navigation notification, whichever
 * way the installed Maps version builds it: the standard title / text / sub-text fields with
 * the arrow as the large icon (current versions), or a custom layout (older versions).
 * Runs off the main thread.
 */
class MapsNoticeReader(private val context: Context) {
    private val arrows = LruCache<Long, ArrowReading>(96)

    fun isNavigation(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (n.category == Notification.CATEGORY_NAVIGATION) return true
        if (!sbn.isOngoing) return false
        if (n.channelId?.contains("navigation", ignoreCase = true) == true) return true
        // An ongoing Maps notification that reads like directions ("300 m", "Rerouting…").
        val g = NoticeParser.parse(textOf(n)) ?: return false
        return g.distanceM != null || g.status != NavStatus.NAVIGATING || g.fromText
    }

    fun read(sbn: StatusBarNotification): MapsRead {
        val n = sbn.notification
        var notice = textOf(n).copy(iconName = smallIconName(n, sbn.packageName))
        var picture = largeIcon(n)
        if (notice.title.isNullOrBlank() && notice.text.isNullOrBlank()) {
            val custom = runCatching { fromLayout(n) }.getOrNull()
            if (custom != null) {
                notice = notice.copy(extraLines = notice.extraLines + custom.first)
                if (picture == null) picture = custom.second
            }
        }
        val arrow = picture?.let { readArrow(it) }
        return MapsRead(notice, picture, arrow)
    }

    private fun textOf(n: Notification): MapsNotice {
        val x = n.extras
        fun s(key: String) = x?.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }
        val lines = x?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.mapNotNull { it?.toString() }.orEmpty()
        return MapsNotice(
            title = s(Notification.EXTRA_TITLE_BIG) ?: s(Notification.EXTRA_TITLE),
            text = s(Notification.EXTRA_TEXT),
            subText = s(Notification.EXTRA_SUB_TEXT),
            bigText = s(Notification.EXTRA_BIG_TEXT),
            extraLines = lines + listOfNotNull(s(Notification.EXTRA_INFO_TEXT), s(Notification.EXTRA_SUMMARY_TEXT)),
        )
    }

    /** Older Maps builds named their small icons after the turn ("da_turn_slight_left"). */
    private fun smallIconName(n: Notification, pkg: String): String? = runCatching {
        val icon = n.smallIcon ?: return null
        if (icon.type != Icon.TYPE_RESOURCE) return null
        val res = context.packageManager.getResourcesForApplication(icon.resPackage ?: pkg)
        res.getResourceEntryName(icon.resId)
    }.getOrNull()

    private fun largeIcon(n: Notification): Bitmap? = runCatching {
        n.getLargeIcon()?.loadDrawable(context)?.let { copyOf(it) }
            ?: (n.extras?.get(Notification.EXTRA_LARGE_ICON) as? Bitmap)?.let { copyOf(BitmapDrawable(context.resources, it)) }
    }.getOrNull()

    /** Draws the picture into a bitmap of our own (the notification's may be recycled later). */
    private fun copyOf(d: Drawable): Bitmap? {
        var w = d.intrinsicWidth
        var h = d.intrinsicHeight
        if (w <= 0 || h <= 0) { w = 128; h = 128 }
        val scale = MAX_SIDE.toFloat() / max(w, h)
        if (scale < 1f) { w = (w * scale).roundToInt().coerceAtLeast(1); h = (h * scale).roundToInt().coerceAtLeast(1) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return bmp
    }

    private fun readArrow(b: Bitmap): ArrowReading? = runCatching {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var h = 1125899906842597L
        for (p in px) h = 31 * h + p
        h = 31 * h + b.width
        arrows.get(h) ?: ArrowReader.read(b.width, b.height, px).also { arrows.put(h, it) }
    }.getOrNull()

    /** Older Google Maps: a custom layout with the distance, road, time and arrow in their own views. */
    private fun fromLayout(n: Notification): Pair<List<String>, Bitmap?>? {
        @Suppress("DEPRECATION")
        val rv: RemoteViews = n.bigContentView ?: n.contentView ?: return null
        val root = rv.apply(context, FrameLayout(context))
        val texts = ArrayList<Pair<String, String>>()
        var picture: Bitmap? = null
        fun walk(v: View) {
            val name = if (v.id != View.NO_ID) runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull().orEmpty() else ""
            when (v) {
                is TextView -> v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && v.visibility == View.VISIBLE }?.let { texts += name to it }
                is ImageView -> if (picture == null && (name.contains("icon") || name.contains("arrow") || name.contains("maneuver")) && !name.contains("small")) {
                    picture = v.drawable?.let { copyOf(it) }
                }
                is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        // Known Maps ids first (title = distance, description = road, time = summary), then the rest.
        val order = listOf("nav_title", "nav_description", "nav_time")
        val sorted = texts.sortedBy { (id, _) -> order.indexOf(id).let { if (it < 0) order.size else it } }
        return sorted.map { it.second }.distinct() to picture
    }

    private companion object {
        const val MAX_SIDE = 256
    }
}
