package com.nv.dronemapping.dji

import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.MissionPlan
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

object KmzExporter {
    // Consumer DJI Fly namespace found in native RC2 waypoint missions.
    private const val NS = "http://www.uav.com/wpmz/1.0.2"

    fun writeKmz(plan: MissionPlan, partIndex: Int, missionName: String, output: OutputStream) {
        require(partIndex in plan.parts.indices)
        val points = plan.parts[partIndex]
        require(points.size >= 2)

        val name = if (plan.parts.size > 1) {
            "${missionName}_part_${partIndex + 1}_of_${plan.parts.size}"
        } else missionName

        val timestamp = System.currentTimeMillis()
        val template = templateKml(plan, name, timestamp)
        val waylines = waylinesWpml(plan, points)

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
        val routeCoords = plan.waypoints.joinToString(" ") {
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
        val config = missionConfig(s.droneEnumValue, s.finishAction, s.rcLostAction, s.speedMs)
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

    private fun waylinesWpml(plan: MissionPlan, points: List<LatLng>): String {
        val s = plan.settings
        val config = missionConfig(s.droneEnumValue, s.finishAction, s.rcLostAction, s.speedMs)
        var groupId = 1
        val placemarks = buildString {
            points.forEachIndexed { index, p ->
                val actions = buildString {
                    if (index == 0) append(gimbalAction(groupId++, index, s.gimbalPitchDeg))
                    append(takePhotoAction(groupId++, index))
                }
                append(placemark(index, p, s.altitudeM, s.speedMs, s.gimbalPitchDeg, actions))
                append('\n')
            }
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:wpml="$NS">
<Document>
$config
  <Folder>
    <wpml:templateId>0</wpml:templateId>
    <wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>
    <wpml:waylineId>0</wpml:waylineId>
    <wpml:distance>0</wpml:distance>
    <wpml:duration>0</wpml:duration>
    <wpml:autoFlightSpeed>${n(s.speedMs)}</wpml:autoFlightSpeed>
$placemarks  </Folder>
</Document>
</kml>
"""
    }

    private fun missionConfig(droneEnum: Int, finishAction: String, rcLostAction: String, speed: Double): String {
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

    private fun placemark(index: Int, p: LatLng, altitude: Double, speed: Double, pitch: Double, actions: String): String {
        return """    <Placemark>
      <Point><coordinates>${f("%.8f,%.8f", p.lon, p.lat)}</coordinates></Point>
      <wpml:index>$index</wpml:index>
      <wpml:executeHeight>${n(altitude)}</wpml:executeHeight>
      <wpml:waypointSpeed>${n(speed)}</wpml:waypointSpeed>
      <wpml:waypointHeadingParam>
        <wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>
        <wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle>
        <wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint>
        <wpml:waypointHeadingAngleEnable>0</wpml:waypointHeadingAngleEnable>
        <wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode>
        <wpml:waypointHeadingPoiIndex>0</wpml:waypointHeadingPoiIndex>
      </wpml:waypointHeadingParam>
      <wpml:waypointTurnParam>
        <wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode>
        <wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist>
      </wpml:waypointTurnParam>
      <wpml:useStraightLine>1</wpml:useStraightLine>
$actions      <wpml:waypointGimbalHeadingParam>
        <wpml:waypointGimbalPitchAngle>${n(pitch)}</wpml:waypointGimbalPitchAngle>
        <wpml:waypointGimbalYawAngle>0</wpml:waypointGimbalYawAngle>
      </wpml:waypointGimbalHeadingParam>
    </Placemark>"""
    }

    private fun takePhotoAction(groupId: Int, index: Int): String = """      <wpml:actionGroup>
        <wpml:actionGroupId>$groupId</wpml:actionGroupId>
        <wpml:actionGroupStartIndex>$index</wpml:actionGroupStartIndex>
        <wpml:actionGroupEndIndex>$index</wpml:actionGroupEndIndex>
        <wpml:actionGroupMode>parallel</wpml:actionGroupMode>
        <wpml:actionTrigger><wpml:actionTriggerType>reachPoint</wpml:actionTriggerType></wpml:actionTrigger>
        <wpml:action>
          <wpml:actionId>$groupId</wpml:actionId>
          <wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>
          <wpml:actionActuatorFuncParam><wpml:payloadPositionIndex>0</wpml:payloadPositionIndex></wpml:actionActuatorFuncParam>
        </wpml:action>
      </wpml:actionGroup>
"""

    private fun gimbalAction(groupId: Int, index: Int, pitch: Double): String = """      <wpml:actionGroup>
        <wpml:actionGroupId>$groupId</wpml:actionGroupId>
        <wpml:actionGroupStartIndex>$index</wpml:actionGroupStartIndex>
        <wpml:actionGroupEndIndex>$index</wpml:actionGroupEndIndex>
        <wpml:actionGroupMode>parallel</wpml:actionGroupMode>
        <wpml:actionTrigger><wpml:actionTriggerType>reachPoint</wpml:actionTriggerType></wpml:actionTrigger>
        <wpml:action>
          <wpml:actionId>$groupId</wpml:actionId>
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
      </wpml:actionGroup>
"""

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun n(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun f(fmt: String, vararg args: Any): String = String.format(Locale.US, fmt, *args)
}
