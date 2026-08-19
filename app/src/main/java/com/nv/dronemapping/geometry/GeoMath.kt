package com.nv.dronemapping.geometry

import com.nv.dronemapping.model.LatLng
import kotlin.math.*

object GeoMath {
    private const val R = 6378137.0

    data class XY(val x: Double, val y: Double)
    data class Projection(val lat0Rad: Double, val lon0Rad: Double) {
        fun toXY(p: LatLng): XY {
            val lat = Math.toRadians(p.lat)
            val lon = Math.toRadians(p.lon)
            return XY(
                x = (lon - lon0Rad) * cos(lat0Rad) * R,
                y = (lat - lat0Rad) * R
            )
        }

        fun toLatLng(p: XY): LatLng {
            val lat = p.y / R + lat0Rad
            val lon = p.x / (R * cos(lat0Rad)) + lon0Rad
            return LatLng(Math.toDegrees(lat), Math.toDegrees(lon))
        }
    }

    fun projectionFor(points: List<LatLng>): Projection {
        require(points.isNotEmpty())
        val lat0 = points.map { it.lat }.average()
        val lon0 = points.map { it.lon }.average()
        return Projection(Math.toRadians(lat0), Math.toRadians(lon0))
    }

    fun polygonAreaM2(points: List<LatLng>): Double {
        if (points.size < 3) return 0.0
        val p = projectionFor(points)
        val xy = points.map(p::toXY)
        var sum = 0.0
        for (i in xy.indices) {
            val a = xy[i]
            val b = xy[(i + 1) % xy.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    fun distanceM(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2.0)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    fun polylineDistanceM(points: List<LatLng>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += distanceM(points[i - 1], points[i])
        return total
    }

    fun bearingDeg(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
