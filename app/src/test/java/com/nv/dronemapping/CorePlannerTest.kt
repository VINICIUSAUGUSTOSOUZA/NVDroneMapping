package com.nv.dronemapping

import com.nv.dronemapping.dji.KmzExporter
import com.nv.dronemapping.geometry.GridPlanner
import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.MissionPlan
import com.nv.dronemapping.model.MissionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos

class CorePlannerTest {
    private fun rect(
        centerLat: Double,
        centerLon: Double,
        widthM: Double,
        heightM: Double
    ): List<LatLng> {
        val latDeg = heightM / 111_320.0 / 2.0
        val lonDeg = widthM / (111_320.0 * cos(Math.toRadians(centerLat))) / 2.0
        return listOf(
            LatLng(centerLat - latDeg, centerLon - lonDeg),
            LatLng(centerLat - latDeg, centerLon + lonDeg),
            LatLng(centerLat + latDeg, centerLon + lonDeg),
            LatLng(centerLat + latDeg, centerLon - lonDeg)
        )
    }

    private fun readKmz(out: ByteArrayOutputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun exportPart(plan: MissionPlan, partIndex: Int): String {
        val out = ByteArrayOutputStream()
        KmzExporter.writeKmz(plan, partIndex, "Unit Test", out)
        val entries = readKmz(out)
        assertEquals(
            setOf("wpmz/template.kml", "wpmz/waylines.wpml"),
            entries.keys
        )

        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        entries.values.forEach {
            dbf.newDocumentBuilder().parse(ByteArrayInputStream(it))
        }

        return entries.getValue("wpmz/waylines.wpml").toString(Charsets.UTF_8)
    }

    @Test
    fun narrowAreaStillGeneratesFlightLineAndExplicitRoute() {
        val plan = GridPlanner.plan(
            rect(-26.1, -48.62, 8.0, 100.0),
            MissionSettings()
        )

        assertTrue(plan.photoPoints.size >= 2)
        assertTrue(plan.stats.flightLineCount >= 1)
        assertTrue(plan.surveyLines.isNotEmpty())
        assertTrue(plan.routeWaypoints.size >= 2)
    }

    @Test
    fun denseMissionSplitsByRealDjiWaypointLimit() {
        val settings = MissionSettings(
            altitudeM = 30.0,
            frontOverlapPct = 90.0,
            sideOverlapPct = 90.0,
            maxWaypointsPerMission = 40
        )
        val plan = GridPlanner.plan(
            rect(-26.1, -48.62, 600.0, 600.0),
            settings
        )

        assertTrue(plan.parts.size > 1)

        plan.parts.indices.forEach { partIndex ->
            val waylines = exportPart(plan, partIndex)
            val placemarks = Regex("<Placemark>").findAll(waylines).count()
            assertTrue(
                "Parte $partIndex excedeu o limite DJI: $placemarks",
                placemarks <= settings.maxWaypointsPerMission
            )
        }
    }

    @Test
    fun plannerSeparatesPhotoPointsFromDjiRouteGeometry() {
        val settings = MissionSettings(
            altitudeM = 45.0,
            frontOverlapPct = 85.0,
            sideOverlapPct = 75.0
        )
        val plan = GridPlanner.plan(
            rect(-26.1, -48.62, 220.0, 160.0),
            settings
        )

        assertTrue(plan.surveyLines.size >= 2)
        assertTrue(plan.photoPoints.size > plan.surveyLines.size * 2)
        assertTrue(plan.routeWaypoints.size < plan.photoPoints.size)

        plan.surveyLines.forEach { line ->
            assertTrue(line.photoStartIndex >= 0)
            assertTrue(line.photoEndIndex >= line.photoStartIndex)
            assertTrue(line.photoEndIndex < plan.photoPoints.size)
            assertTrue(line.photoSpacingM > 0.0)
        }
    }

    @Test
    fun exportedKmzContainsContinuousDjiRouteAndDistancePhotos() {
        val settings = MissionSettings(
            altitudeM = 73.0,
            speedMs = 6.0,
            frontOverlapPct = 85.0,
            sideOverlapPct = 75.0,
            maxWaypointsPerMission = 200
        )
        val plan = GridPlanner.plan(
            rect(-26.1, -48.62, 180.0, 120.0),
            settings
        )
        assertEquals(1, plan.parts.size)

        val waylines = exportPart(plan, 0)
        val distanceTriggers = Regex(
            "<wpml:actionTriggerType>multipleDistance</wpml:actionTriggerType>"
        ).findAll(waylines).count()
        val placemarks = Regex("<Placemark>").findAll(waylines).count()

        assertEquals(plan.surveyLines.size, distanceTriggers)
        assertTrue(waylines.contains("toPointAndPassWithContinuityCurvature"))
        assertTrue(waylines.contains("<wpml:useStraightLine>1</wpml:useStraightLine>"))
        assertTrue(waylines.contains("<wpml:executeHeight>73.0</wpml:executeHeight>"))
        assertTrue(waylines.contains("<wpml:waypointSpeed>6.0</wpml:waypointSpeed>"))
        assertTrue(waylines.contains("<wpml:waypointGimbalPitchAngle>-90.0</wpml:waypointGimbalPitchAngle>"))
        assertTrue(placemarks < plan.photoPoints.size)
        assertTrue(placemarks <= plan.routeWaypoints.size)
    }

    @Test
    fun legacyPlanWithoutSurveyLinesIsBlockedInsteadOfExportingStopAndShoot() {
        val plan = GridPlanner.plan(
            rect(-26.1, -48.62, 120.0, 80.0),
            MissionSettings()
        )
        val legacy = plan.copy(
            surveyLines = emptyList(),
            routeWaypoints = emptyList()
        )

        val error = runCatching {
            KmzExporter.writeKmz(
                legacy,
                0,
                "Legacy",
                ByteArrayOutputStream()
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("versão antiga"))
    }
}
