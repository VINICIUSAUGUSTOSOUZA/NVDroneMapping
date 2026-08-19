package com.nv.dronemapping

import com.nv.dronemapping.dji.KmzExporter
import com.nv.dronemapping.geometry.GridPlanner
import com.nv.dronemapping.model.LatLng
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
    private fun rect(centerLat: Double, centerLon: Double, widthM: Double, heightM: Double): List<LatLng> {
        val latDeg = heightM / 111_320.0 / 2.0
        val lonDeg = widthM / (111_320.0 * cos(Math.toRadians(centerLat))) / 2.0
        return listOf(
            LatLng(centerLat - latDeg, centerLon - lonDeg),
            LatLng(centerLat - latDeg, centerLon + lonDeg),
            LatLng(centerLat + latDeg, centerLon + lonDeg),
            LatLng(centerLat + latDeg, centerLon - lonDeg)
        )
    }

    @Test
    fun narrowAreaStillGeneratesFlightLine() {
        val plan = GridPlanner.plan(rect(-26.1, -48.62, 8.0, 100.0), MissionSettings())
        assertTrue(plan.waypoints.size >= 2)
        assertTrue(plan.stats.flightLineCount >= 1)
    }

    @Test
    fun denseMissionIsSplitBelowWaypointLimit() {
        val settings = MissionSettings(altitudeM = 30.0, frontOverlapPct = 90.0, sideOverlapPct = 90.0)
        val plan = GridPlanner.plan(rect(-26.1, -48.62, 600.0, 600.0), settings)
        assertTrue(plan.parts.size > 1)
        assertTrue(plan.parts.all { it.size <= 190 })
    }

    @Test
    fun exportedKmzContainsDjiStructureAndAutomaticPhotoActions() {
        val plan = GridPlanner.plan(rect(-26.1, -48.62, 120.0, 80.0), MissionSettings())
        val out = ByteArrayOutputStream()
        KmzExporter.writeKmz(plan, 0, "Unit Test", out)

        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        assertEquals(setOf("wpmz/template.kml", "wpmz/waylines.wpml"), entries.keys)
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        entries.values.forEach { dbf.newDocumentBuilder().parse(ByteArrayInputStream(it)) }

        val waylines = entries.getValue("wpmz/waylines.wpml").toString(Charsets.UTF_8)
        val actions = Regex("<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>").findAll(waylines).count()
        assertEquals(plan.parts[0].size, actions)
        assertTrue(waylines.contains("toPointAndStopWithDiscontinuityCurvature"))
    }
}
