package com.nv.dronemapping.planning

import com.nv.dronemapping.model.LatLng
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
    fun batterySplitRepeatsConfiguredPhotos() {
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
}
