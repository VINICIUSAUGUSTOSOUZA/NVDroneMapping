package com.nv.dronemapping.dji

import com.nv.dronemapping.geometry.GeoMath
import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.MissionPlan
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

object KmzExporter {
    // Namespace observado em missões reais salvas pelo DJI Fly.
    private const val NS = "http://www.uav.com/wpmz/1.0.2"

    private data class ExportLine(
        val startIndex: Int,
        val endIndex: Int,
        val photoSpacingM: Double
    )

    private data class ExportRoute(
        val points: List<LatLng>,
        val lines: List<ExportLine>
    )

    fun writeKmz(
        plan: MissionPlan,
        partIndex: Int,
        missionName: String,
        output: OutputStream
    ) {
        require(partIndex in plan.parts.indices)
        require(plan.surveyLines.isNotEmpty()) {
            "Este plano é de uma versão antiga. Gere o plano novamente antes de exportar para DJI."
        }
        require(plan.photoPoints.size >= 2) { "Missão sem pontos de foto suficientes." }

        val photoPart = plan.parts[partIndex]
        require(photoPart.size >= 2)

        val name = if (plan.parts.size > 1) {
            "${missionName}_part_${partIndex + 1}_of_${plan.parts.size}"
        } else {
            missionName
        }

        val route = buildExportRoute(plan, partIndex)
        require(route.points.size >= 2) { "Parte sem geometria suficiente para exportar." }
        require(route.lines.isNotEmpty()) { "Parte sem faixa de fotografia válida." }
        require(route.points.size <= 200) {
            "Esta parte excede 200 waypoints DJI. Divida a missão em mais partes."
        }

        val timestamp = System.currentTimeMillis()
        val template = templateKml(plan, name, timestamp)
        val waylines = waylinesWpml(plan, route)

        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("wpmz/template.kml"))
            zip.write(template.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("wpmz/waylines.wpml"))
            zip.write(waylines.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun writePreviewKml(plan: MissionPlan, output: OutputStream) {
        val boundaryCoords = (plan.boundary + plan.boundary.first()).joinToString(" ") {
            f("%.8f,%.8f,0", it.lon, it.lat)
        }
        val routePoints = plan.routeWaypoints.takeIf { it.size >= 2 }
            ?: plan.surveyLines.flatMap { listOf(it.start, it.end) }
        val routeCoords = routePoints.joinToString(" ") {
            f("%.8f,%.8f,%.1f", it.lon, it.lat, plan.settings.altitudeM)
        }
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
  <name>NV Drone Mapping - Preview</name>
  <Placemark><name>Área</name><Polygon><outerBoundaryIs><LinearRing><coordinates>$boundaryCoords</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
  <Placemark><name>Rota</name><LineString><altitudeMode>relativeToGround</altitudeMode><coordinates>$routeCoords</coordinates></LineString></Placemark>
</Document>
</kml>"""
        output.write(xml.toByteArray(StandardCharsets.UTF_8))
    }

    private fun templateKml(plan: MissionPlan, missionName: String, ts: Long): String {
        val s = plan.settings
        val config = missionConfig(
            s.droneEnumValue,
            s.finishAction,
            s.rcLostAction,
            s.speedMs
        )

        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:wpml="$NS">
<Document>
  <wpml:author>${esc(missionName)}</wpml:author>
  <wpml:createTime>$ts</wpml:createTime>
  <wpml:updateTime>$ts</wpml:updateTime>
$config
  <Folder>
    <wpml:templateType>waypoint</wpml:templateType>
    <wpml:templateId>0</wpml:templateId>
    <wpml:waylineCoordinateSysParam>
      <wpml:coordinateMode>WGS84</wpml:coordinateMode>
      <wpml:heightMode>relativeToStartPoint</wpml:heightMode>
      <wpml:positioningType>GPS</wpml:positioningType>
    </wpml:waylineCoordinateSysParam>
    <wpml:autoFlightSpeed>${n(s.speedMs)}</wpml:autoFlightSpeed>
    <wpml:globalHeight>${n(s.altitudeM)}</wpml:globalHeight>
    <wpml:caliFlightEnable>0</wpml:caliFlightEnable>
    <wpml:gimbalPitchMode>usePointSetting</wpml:gimbalPitchMode>
  </Folder>
</Document>
</kml>
"""
    }

    private fun waylinesWpml(plan: MissionPlan, route: ExportRoute): String {
        val s = plan.settings
        val config = missionConfig(
            s.droneEnumValue,
            s.finishAction,
            s.rcLostAction,
            s.speedMs
        )

        var nextGroupId = 0
        val actionsByWaypoint = mutableMapOf<Int, StringBuilder>()

        fun actionsAt(index: Int): StringBuilder =
            actionsByWaypoint.getOrPut(index) { StringBuilder() }

        route.lines.forEachIndexed { lineIndex, line ->
            if (lineIndex == 0) {
                actionsAt(line.startIndex).append(
                    gimbalAndPhotoAction(
                        groupId = nextGroupId++,
                        index = line.startIndex,
                        pitch = s.gimbalPitchDeg
                    )
                )
            } else {
                actionsAt(line.startIndex).append(
                    takePhotoAtPointAction(
                        groupId = nextGroupId++,
                        index = line.startIndex
                    )
                )
            }

            actionsAt(line.startIndex).append(
                takePhotoByDistanceAction(
                    groupId = nextGroupId++,
                    startIndex = line.startIndex,
                    endIndex = line.endIndex,
                    spacingM = line.photoSpacingM
                )
            )
        }

        val baseDamping = min(
            5.0,
            max(1.0, plan.stats.photoSpacingM * 0.40)
        )

        val placemarks = buildString {
            route.points.forEachIndexed { index, point ->
                val turn = turnForWaypoint(
                    index = index,
                    points = route.points,
                    desiredDampingM = baseDamping
                )
                append(
                    placemark(
                        index = index,
                        p = point,
                        altitude = s.altitudeM,
                        speed = s.speedMs,
                        pitch = s.gimbalPitchDeg,
                        turnMode = turn.first,
                        dampingM = turn.second,
                        actions = actionsByWaypoint[index]?.toString().orEmpty()
                    )
                )
                append('\n')
            }
        }

        val distance = GeoMath.polylineDistanceM(route.points)
        val duration = if (s.speedMs > 0.0) distance / s.speedMs else 0.0

        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:wpml="$NS">
<Document>
$config
  <Folder>
    <wpml:templateId>0</wpml:templateId>
    <wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>
    <wpml:waylineId>0</wpml:waylineId>
    <wpml:distance>${n(distance)}</wpml:distance>
    <wpml:duration>${n(duration)}</wpml:duration>
    <wpml:autoFlightSpeed>${n(s.speedMs)}</wpml:autoFlightSpeed>
$placemarks  </Folder>
</Document>
</kml>
"""
    }

    private fun buildExportRoute(
        plan: MissionPlan,
        partIndex: Int
    ): ExportRoute {
        val partRange = findPartPhotoRange(plan, partIndex)
            ?: error("Não foi possível localizar a faixa de fotos desta parte.")

        val routePoints = mutableListOf<LatLng>()
        val exportLines = mutableListOf<ExportLine>()

        fun appendRoutePoint(point: LatLng): Int {
            val last = routePoints.lastOrNull()
            if (last != null && GeoMath.distanceM(last, point) < 0.05) {
                return routePoints.lastIndex
            }
            routePoints += point
            return routePoints.lastIndex
        }

        plan.surveyLines.forEach { line ->
            if (line.photoEndIndex < partRange.first || line.photoStartIndex > partRange.last) {
                return@forEach
            }

            val photoStart = max(line.photoStartIndex, partRange.first)
            val photoEnd = min(line.photoEndIndex, partRange.last)
            if (photoEnd <= photoStart) return@forEach

            val startPoint = plan.photoPoints.getOrNull(photoStart) ?: line.start
            val endPoint = plan.photoPoints.getOrNull(photoEnd) ?: line.end
            if (GeoMath.distanceM(startPoint, endPoint) < 0.50) return@forEach

            val startIndex = appendRoutePoint(startPoint)
            val endIndex = appendRoutePoint(endPoint)
            if (endIndex <= startIndex) return@forEach

            exportLines += ExportLine(
                startIndex = startIndex,
                endIndex = endIndex,
                photoSpacingM = line.photoSpacingM.coerceAtLeast(0.5)
            )
        }

        require(routePoints.size >= 2 && exportLines.isNotEmpty()) {
            "A parte não contém faixa contínua válida. Gere o plano novamente."
        }

        return ExportRoute(
            points = routePoints,
            lines = exportLines
        )
    }

    private fun findPartPhotoRange(plan: MissionPlan, targetPartIndex: Int): IntRange? {
        var searchCursor = 0
        var currentRange: IntRange? = null

        for (partIndex in 0..targetPartIndex) {
            val part = plan.parts.getOrNull(partIndex) ?: return null
            val searchStart = max(0, searchCursor - 25)
            val found = findSubsequence(
                all = plan.photoPoints,
                sub = part,
                fromIndex = searchStart
            )
            if (found < 0) return null

            currentRange = found..(found + part.lastIndex)
            searchCursor = currentRange.last
        }

        return currentRange
    }

    private fun findSubsequence(
        all: List<LatLng>,
        sub: List<LatLng>,
        fromIndex: Int
    ): Int {
        if (sub.isEmpty() || sub.size > all.size) return -1
        val lastStart = all.size - sub.size

        for (start in fromIndex.coerceAtLeast(0)..lastStart) {
            var matches = true
            for (offset in sub.indices) {
                if (all[start + offset] != sub[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }

        return -1
    }

    private fun turnForWaypoint(
        index: Int,
        points: List<LatLng>,
        desiredDampingM: Double
    ): Pair<String, Double> {
        if (index == 0 || index == points.lastIndex) {
            return "toPointAndStopWithDiscontinuityCurvature" to 0.0
        }

        val previousDistance = GeoMath.distanceM(points[index - 1], points[index])
        val nextDistance = GeoMath.distanceM(points[index], points[index + 1])
        val safeDamping = min(previousDistance, nextDistance) * 0.20

        if (safeDamping < 0.50) {
            return "toPointAndStopWithDiscontinuityCurvature" to 0.0
        }

        return "toPointAndPassWithContinuityCurvature" to
            min(desiredDampingM, safeDamping).coerceAtLeast(0.50)
    }

    private fun missionConfig(
        droneEnum: Int,
        finishAction: String,
        rcLostAction: String,
        speed: Double
    ): String {
        val (exitOnLost, executeLost) = if (rcLostAction == "goContinue") {
            "goContinue" to "goBack"
        } else {
            "executeLostAction" to rcLostAction
        }
        val finish = finishAction.ifBlank { "goHome" }
        val transitional = min(speed, 5.0)

        return """  <wpml:missionConfig>
    <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>
    <wpml:finishAction>$finish</wpml:finishAction>
    <wpml:exitOnRCLost>$exitOnLost</wpml:exitOnRCLost>
    <wpml:executeRCLostAction>$executeLost</wpml:executeRCLostAction>
    <wpml:globalTransitionalSpeed>${n(transitional)}</wpml:globalTransitionalSpeed>
    <wpml:droneInfo>
      <wpml:droneEnumValue>$droneEnum</wpml:droneEnumValue>
      <wpml:droneSubEnumValue>0</wpml:droneSubEnumValue>
    </wpml:droneInfo>
  </wpml:missionConfig>"""
    }

    private fun placemark(
        index: Int,
        p: LatLng,
        altitude: Double,
        speed: Double,
        pitch: Double,
        turnMode: String,
        dampingM: Double,
        actions: String
    ): String {
        return """    <Placemark>
      <Point><coordinates>${f("%.8f,%.8f", p.lon, p.lat)}</coordinates></Point>
      <wpml:index>$index</wpml:index>
      <wpml:executeHeight>${n(altitude)}</wpml:executeHeight>
      <wpml:waypointSpeed>${n(speed)}</wpml:waypointSpeed>
      <wpml:waypointHeadingParam>
        <wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>
        <wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle>
        <wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint>
        <wpml:waypointHeadingAngleEnable>1</wpml:waypointHeadingAngleEnable>
        <wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode>
        <wpml:waypointHeadingPoiIndex>0</wpml:waypointHeadingPoiIndex>
      </wpml:waypointHeadingParam>
      <wpml:waypointTurnParam>
        <wpml:waypointTurnMode>$turnMode</wpml:waypointTurnMode>
        <wpml:waypointTurnDampingDist>${n(dampingM)}</wpml:waypointTurnDampingDist>
      </wpml:waypointTurnParam>
      <wpml:useStraightLine>1</wpml:useStraightLine>
$actions      <wpml:waypointGimbalHeadingParam>
        <wpml:waypointGimbalPitchAngle>${n(pitch)}</wpml:waypointGimbalPitchAngle>
        <wpml:waypointGimbalYawAngle>0</wpml:waypointGimbalYawAngle>
      </wpml:waypointGimbalHeadingParam>
    </Placemark>"""
    }

    private fun takePhotoAtPointAction(groupId: Int, index: Int): String = """      <wpml:actionGroup>
        <wpml:actionGroupId>$groupId</wpml:actionGroupId>
        <wpml:actionGroupStartIndex>$index</wpml:actionGroupStartIndex>
        <wpml:actionGroupEndIndex>$index</wpml:actionGroupEndIndex>
        <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
        <wpml:actionTrigger>
          <wpml:actionTriggerType>reachPoint</wpml:actionTriggerType>
        </wpml:actionTrigger>
        <wpml:action>
          <wpml:actionId>$groupId</wpml:actionId>
          <wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>
          <wpml:actionActuatorFuncParam>
            <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
          </wpml:actionActuatorFuncParam>
        </wpml:action>
      </wpml:actionGroup>
"""

    private fun takePhotoByDistanceAction(
        groupId: Int,
        startIndex: Int,
        endIndex: Int,
        spacingM: Double
    ): String = """      <wpml:actionGroup>
        <wpml:actionGroupId>$groupId</wpml:actionGroupId>
        <wpml:actionGroupStartIndex>$startIndex</wpml:actionGroupStartIndex>
        <wpml:actionGroupEndIndex>$endIndex</wpml:actionGroupEndIndex>
        <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
        <wpml:actionTrigger>
          <wpml:actionTriggerType>multipleDistance</wpml:actionTriggerType>
          <wpml:actionTriggerParam>${n(spacingM)}</wpml:actionTriggerParam>
        </wpml:actionTrigger>
        <wpml:action>
          <wpml:actionId>$groupId</wpml:actionId>
          <wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>
          <wpml:actionActuatorFuncParam>
            <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
          </wpml:actionActuatorFuncParam>
        </wpml:action>
      </wpml:actionGroup>
"""

    private fun gimbalAndPhotoAction(
        groupId: Int,
        index: Int,
        pitch: Double
    ): String = """      <wpml:actionGroup>
        <wpml:actionGroupId>$groupId</wpml:actionGroupId>
        <wpml:actionGroupStartIndex>$index</wpml:actionGroupStartIndex>
        <wpml:actionGroupEndIndex>$index</wpml:actionGroupEndIndex>
        <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
        <wpml:actionTrigger>
          <wpml:actionTriggerType>reachPoint</wpml:actionTriggerType>
        </wpml:actionTrigger>
        <wpml:action>
          <wpml:actionId>${groupId * 10}</wpml:actionId>
          <wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>
          <wpml:actionActuatorFuncParam>
            <wpml:gimbalHeadingYawBase>aircraft</wpml:gimbalHeadingYawBase>
            <wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>
            <wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>
            <wpml:gimbalPitchRotateAngle>${n(pitch)}</wpml:gimbalPitchRotateAngle>
            <wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable>
            <wpml:gimbalRollRotateAngle>0</wpml:gimbalRollRotateAngle>
            <wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable>
            <wpml:gimbalYawRotateAngle>0</wpml:gimbalYawRotateAngle>
            <wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable>
            <wpml:gimbalRotateTime>0</wpml:gimbalRotateTime>
            <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
          </wpml:actionActuatorFuncParam>
        </wpml:action>
        <wpml:action>
          <wpml:actionId>${groupId * 10 + 1}</wpml:actionId>
          <wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>
          <wpml:actionActuatorFuncParam>
            <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
          </wpml:actionActuatorFuncParam>
        </wpml:action>
      </wpml:actionGroup>
"""

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun n(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun f(fmt: String, vararg args: Any): String =
        String.format(Locale.US, fmt, *args)
}
