package com.nv.dronemapping.planning

import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.SurveyLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMissionPlannerTest {

    @Test
    fun altitudeForOnePointFiveCmIsAroundExpectedRange() {
        val altitude = SmartMissionPlanner.altitudeForGsd(1.5)
        assertTrue(altitude in 80.0..90.0)
    }

    @Test
    fun batterySplitRepeatsConfiguredPhotosInLegacyFallback() {
        val points = (0..40).map { i ->
            LatLng(-26.0, -48.0 + i * 0.0001)
        }

        val plan = SmartMissionPlanner.splitByBattery(
            waypoints = points,
            speedMs = 5.0,
            maxWaypointsPerMission = 20,
            options = SmartMissionPlanner.Options(
                nominalBatteryMinutes = 30.0,
                reservePct = 25.0,
                overlapPhotos = 5
            ),
            home = points.first()
        )

        assertTrue(plan.parts.size >= 2)
        val firstEnd = plan.infos.first().endPhotoNumber
        val secondStart = plan.infos[1].startPhotoNumber
        assertEquals(5, firstEnd - secondStart + 1)
    }

    @Test
    fun continuousCaptureDoesNotAddStopTimePerPhoto() {
        val points = (0..10).map { i ->
            LatLng(-26.0, -48.0 + i * 0.0001)
        }

        val plan = SmartMissionPlanner.splitByBattery(
            waypoints = points,
            speedMs = 5.0,
            maxWaypointsPerMission = 20,
            options = SmartMissionPlanner.Options(
                nominalBatteryMinutes = 30.0,
                reservePct = 25.0,
                overlapPhotos = 0
            ),
            home = null
        )

        val surveySeconds = plan.infos.first().surveySeconds
        assertTrue(surveySeconds > 0.0)
        assertTrue(surveySeconds < 30.0)
    }

    @Test
    fun batterySplitPrefersSurveyLineEndsAndCountsRealDjiWaypoints() {
        // 12 faixas x 10 fotos. Com limite mínimo válido de 20 WP DJI,
        // a primeira parte pode conter no máximo 10 faixas (20 WP reais).
        val points = (0..119).map { i ->
            LatLng(-26.0, -48.0 + i * 0.00001)
        }
        val lines = (0 until 12).map { lineIndex ->
            val start = lineIndex * 10
            val end = start + 9
            SurveyLine(
                start = points[start],
                end = points[end],
                photoStartIndex = start,
                photoEndIndex = end,
                photoSpacingM = 10.0
            )
        }

        val plan = SmartMissionPlanner.splitByBattery(
            waypoints = points,
            speedMs = 10.0,
            maxWaypointsPerMission = 20,
            options = SmartMissionPlanner.Options(
                nominalBatteryMinutes = 30.0,
                reservePct = 25.0,
                overlapPhotos = 0
            ),
            home = null,
            surveyLines = lines
        )

        assertEquals(2, plan.parts.size)
        assertEquals(100, plan.infos[0].endPhotoNumber)
        assertEquals(20, plan.infos[0].waypointCount)
        assertEquals(101, plan.infos[1].startPhotoNumber)
        assertEquals(120, plan.infos[1].endPhotoNumber)
        assertEquals(4, plan.infos[1].waypointCount)
    }
}
