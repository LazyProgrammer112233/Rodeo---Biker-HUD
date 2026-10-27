package com.rodeo.hud.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6_371_000.0

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing in degrees 0..360 from point 1 to point 2. */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Smallest absolute angle between two bearings. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = ((a - b) % 360 + 360) % 360
        return if (d > 180) 360 - d else d
    }

    /** Moves a point by metres east / north. */
    fun offset(lat: Double, lng: Double, eastM: Double, northM: Double): Pair<Double, Double> {
        val dLat = northM / R
        val dLng = eastM / (R * cos(Math.toRadians(lat)))
        return Pair(lat + Math.toDegrees(dLat), lng + Math.toDegrees(dLng))
    }

    fun mapsLink(lat: Double, lng: Double) = "https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.US, lat, lng)
}
