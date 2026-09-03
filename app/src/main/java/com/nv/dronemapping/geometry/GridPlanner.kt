package com.nv.dronemapping.geometry

import com.nv.dronemapping.model.CameraModel
import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.MissionPlan
import com.nv.dronemapping.model.MissionSettings
import com.nv.dronemapping.model.MissionStats
import com.nv.dronemapping.model.SurveyLine
import kotlin.math.*

object GridPlanner {

    private data class UV(val u: Double, val v: Double)

    private data class LineResult(
        val start: LatLng,
        val end: LatLng,
        val photoPoints: List<LatLng>,
        val photoSpacingM: Double
    )

    private data class GridResult(
        val lines: List<LineResult>
    ) {
        val photoPoints: List<LatLng>
            get() = lines.flatMap { it.photoPoints }

        val lineCount: Int
            get() = lines.size
    }

    fun plan(
        boundary: List<LatLng>,
        settings: MissionSettings,
        camera: CameraModel = CameraModel()
    ): MissionPlan {
        require(boundary.size >= 3) { "Desenhe pelo menos 3 vértices." }
        require(settings.altitudeM in 10.0..500.0) { "Altura fora do intervalo aceito." }
        require(settings.speedMs in 0.5..15.0) { "Velocidade fora do intervalo aceito." }
        require(settings.frontOverlapPct in 10.0..95.0) { "Sobreposição frontal inválida." }
        require(settings.sideOverlapPct in 10.0..95.0) { "Sobreposição lateral inválida." }

        val footprint = cameraFootprint(settings.altitudeM, camera)
        val desiredPhotoSpacing = max(
            1.0,
            footprint.second * (1.0 - settings.frontOverlapPct / 100.0)
        )
        val lineSpacing = max(
            1.0,
            footprint.first * (1.0 - settings.sideOverlapPct / 100.0)
        )

        val bearing = if (settings.autoBearing) {
            findBestBearing(boundary, lineSpacing)
        } else {
            normalizeBearing(settings.bearingDeg)
        }

        val primary = generateGrid(
            boundary,
            bearing,
            lineSpacing,
            desiredPhotoSpacing
        )

        val secondary = if (settings.crossHatch) {
            generateGrid(
                boundary,
                normalizeBearing(bearing + 90.0),
                lineSpacing,
                desiredPhotoSpacing
            )
        } else {
            null
        }

        val generatedLines = primary.lines + (secondary?.lines ?: emptyList())
        val waypoints = generatedLines.flatMap { it.photoPoints }

        require(waypoints.size >= 2) { "Não foi possível gerar uma rota dentro da área." }

        val surveyLines = buildSurveyLines(generatedLines)
        require(surveyLines.isNotEmpty()) { "Não foi possível gerar faixas de levantamento." }

        val parts = splitMission(
            waypoints,
            settings.maxWaypointsPerMission.coerceIn(20, 200)
        )

        val routePoints = surveyLines.flatMap { listOf(it.start, it.end) }
        val routeDistance = GeoMath.polylineDistanceM(routePoints)
        val estimatedSeconds = routeDistance / settings.speedMs
        val gsdCmPx = footprint.first / camera.imageWidthPx * 100.0
        val lineCount = surveyLines.size

        val effectivePhotoSpacing = surveyLines
            .map { it.photoSpacingM }
            .filter { it > 0.0 }
            .average()
            .takeIf { !it.isNaN() }
            ?: desiredPhotoSpacing

        val stats = MissionStats(
            areaM2 = GeoMath.polygonAreaM2(boundary),
            gsdCmPx = gsdCmPx,
            groundWidthM = footprint.first,
            groundHeightM = footprint.second,
            lineSpacingM = lineSpacing,
            photoSpacingM = effectivePhotoSpacing,
            routeDistanceM = routeDistance,
            estimatedFlightSeconds = estimatedSeconds,
            photoCount = waypoints.size,
            flightLineCount = lineCount,
            partCount = parts.size,
            effectiveBearingDeg = bearing
        )

        return MissionPlan(
            boundary = boundary,
            waypoints = waypoints,
            parts = parts,
            settings = settings.copy(bearingDeg = bearing),
            stats = stats,
            surveyLines = surveyLines
        )
    }

    private fun cameraFootprint(
        altitudeM: Double,
        camera: CameraModel
    ): Pair<Double, Double> {
        val diagTan = tan(Math.toRadians(camera.diagonalFovDeg / 2.0))
        val diag = hypot(camera.aspectWidth, camera.aspectHeight)
        val hTan = diagTan * camera.aspectWidth / diag
        val vTan = diagTan * camera.aspectHeight / diag
        val width = 2.0 * altitudeM * hTan
        val height = 2.0 * altitudeM * vTan
        return width to height
    }

    private fun findBestBearing(
        boundary: List<LatLng>,
        lineSpacing: Double
    ): Double {
        var bestBearing = 0.0
        var bestCost = Double.POSITIVE_INFINITY
        var b = 0.0

        while (b < 180.0) {
            val result = generateGrid(
                boundary = boundary,
                bearingDeg = b,
                lineSpacing = lineSpacing,
                photoSpacing = max(4.0, lineSpacing / 2.0),
                photos = false
            )

            val routePoints = result.lines.flatMap { listOf(it.start, it.end) }
            if (routePoints.size >= 2) {
                val cost = GeoMath.polylineDistanceM(routePoints) + result.lineCount * 8.0
                if (cost < bestCost) {
                    bestCost = cost
                    bestBearing = b
                }
            }

            b += 5.0
        }

        return bestBearing
    }

    private fun generateGrid(
        boundary: List<LatLng>,
        bearingDeg: Double,
        lineSpacing: Double,
        photoSpacing: Double,
        photos: Boolean = true
    ): GridResult {
        val projection = GeoMath.projectionFor(boundary)
        val xy = boundary.map(projection::toXY)
        val br = Math.toRadians(bearingDeg)
        val dX = sin(br)
        val dY = cos(br)
        val nX = cos(br)
        val nY = -sin(br)

        val uv = xy.map { p ->
            UV(
                p.x * dX + p.y * dY,
                p.x * nX + p.y * nY
            )
        }

        val minV = uv.minOf { it.v }
        val maxV = uv.maxOf { it.v }
        val span = maxV - minV
        val lineSlots = max(1, ceil(span / lineSpacing).toInt())
        val actualSpacing = if (span > 0.01) span / lineSlots else lineSpacing

        val lines = mutableListOf<LineResult>()
        var reverse = false

        for (i in 0 until lineSlots) {
            val v = if (span > 0.01) {
                minV + (i + 0.5) * actualSpacing
            } else {
                minV
            }

            val intersections = mutableListOf<Double>()

            for (j in uv.indices) {
                val a = uv[j]
                val b = uv[(j + 1) % uv.size]
                val crosses =
                    (a.v <= v && b.v > v) ||
                        (b.v <= v && a.v > v)

                if (crosses) {
                    val t = (v - a.v) / (b.v - a.v)
                    intersections += a.u + t * (b.u - a.u)
                }
            }

            intersections.sort()
            if (intersections.size < 2) continue

            val segments = mutableListOf<Pair<Double, Double>>()
            var k = 0
            while (k + 1 < intersections.size) {
                val u1 = intersections[k]
                val u2 = intersections[k + 1]
                if (u2 - u1 > 0.5) segments += u1 to u2
                k += 2
            }

            if (segments.isEmpty()) continue

            val orderedSegments = if (reverse) segments.asReversed() else segments

            for (seg in orderedSegments) {
                val startU = if (reverse) seg.second else seg.first
                val endU = if (reverse) seg.first else seg.second
                val delta = endU - startU
                val length = abs(delta)
                if (length < 0.5) continue

                val uValues: List<Double>
                val effectivePhotoSpacing: Double

                if (photos) {
                    val photoCount = max(
                        2,
                        ceil(length / photoSpacing).toInt() + 1
                    )

                    effectivePhotoSpacing = if (photoCount > 1) {
                        length / (photoCount - 1)
                    } else {
                        length
                    }

                    uValues = (0 until photoCount).map { idx ->
                        val t = if (photoCount <= 1) {
                            0.0
                        } else {
                            idx.toDouble() / (photoCount - 1)
                        }
                        startU + delta * t
                    }
                } else {
                    effectivePhotoSpacing = length
                    uValues = listOf(startU, endU)
                }

                val linePoints = uValues.map { u ->
                    val x = u * dX + v * nX
                    val y = u * dY + v * nY
                    projection.toLatLng(GeoMath.XY(x, y))
                }

                if (linePoints.size >= 2) {
                    lines += LineResult(
                        start = linePoints.first(),
                        end = linePoints.last(),
                        photoPoints = linePoints,
                        photoSpacingM = effectivePhotoSpacing
                    )
                }
            }

            reverse = !reverse
        }

        return GridResult(lines)
    }

    private fun buildSurveyLines(lines: List<LineResult>): List<SurveyLine> {
        var photoIndex = 0
        return lines.mapNotNull { line ->
            if (line.photoPoints.size < 2) return@mapNotNull null

            val startIndex = photoIndex
            val endIndex = photoIndex + line.photoPoints.lastIndex
            photoIndex = endIndex + 1

            SurveyLine(
                start = line.start,
                end = line.end,
                photoStartIndex = startIndex,
                photoEndIndex = endIndex,
                photoSpacingM = line.photoSpacingM
            )
        }
    }

    fun orientTowardStart(
        plan: MissionPlan,
        preferredStart: LatLng?
    ): MissionPlan {
        preferredStart ?: return plan
        if (plan.waypoints.size < 2) return plan

        val firstDistance = GeoMath.distanceM(preferredStart, plan.waypoints.first())
        val lastDistance = GeoMath.distanceM(preferredStart, plan.waypoints.last())

        if (firstDistance <= lastDistance) return plan

        val reversedWaypoints = plan.waypoints.asReversed()
        val reversedParts = splitMission(
            reversedWaypoints,
            plan.settings.maxWaypointsPerMission.coerceIn(20, 200)
        )
        val reversedSurveyLines = reverseSurveyLines(
            plan.surveyLines,
            plan.waypoints.size
        )

        return plan.copy(
            waypoints = reversedWaypoints,
            parts = reversedParts,
            surveyLines = reversedSurveyLines,
            stats = plan.stats.copy(partCount = reversedParts.size)
        )
    }

    private fun reverseSurveyLines(
        lines: List<SurveyLine>,
        photoCount: Int
    ): List<SurveyLine> {
        if (lines.isEmpty()) return emptyList()

        return lines.asReversed().map { line ->
            SurveyLine(
                start = line.end,
                end = line.start,
                photoStartIndex = photoCount - 1 - line.photoEndIndex,
                photoEndIndex = photoCount - 1 - line.photoStartIndex,
                photoSpacingM = line.photoSpacingM
            )
        }
    }

    private fun splitMission(
        points: List<LatLng>,
        maxPerPart: Int
    ): List<List<LatLng>> {
        if (points.size <= maxPerPart) return listOf(points)

        val parts = mutableListOf<List<LatLng>>()
        var start = 0

        while (start < points.size - 1) {
            val endExclusive = min(points.size, start + maxPerPart)
            val part = points.subList(start, endExclusive).toList()
            parts += part

            if (endExclusive >= points.size) break
            start = endExclusive - 1
        }

        return parts
    }

    private fun normalizeBearing(value: Double): Double {
        val b = value % 180.0
        return if (b < 0) b + 180.0 else b
    }
}
