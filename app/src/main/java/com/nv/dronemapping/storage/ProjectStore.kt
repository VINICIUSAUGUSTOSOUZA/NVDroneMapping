package com.nv.dronemapping.storage

import android.content.Context
import com.nv.dronemapping.geometry.GeoMath
import com.nv.dronemapping.geometry.GridPlanner
import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.MissionPlan
import com.nv.dronemapping.model.MissionSettings
import com.nv.dronemapping.model.MissionStats
import com.nv.dronemapping.model.SavedProject
import com.nv.dronemapping.model.SurveyLine
import org.json.JSONArray
import org.json.JSONObject

class ProjectStore(context: Context) {

    private val prefs = context.getSharedPreferences(
        "nv_drone_projects",
        Context.MODE_PRIVATE
    )

    fun save(project: SavedProject) {
        val all = loadAll().toMutableList()

        all.removeAll {
            it.name.equals(project.name, true)
        }

        all.add(0, project)

        prefs.edit()
            .putString("projects", encode(all.take(50)))
            .apply()
    }

    fun loadAll(): List<SavedProject> {
        val raw = prefs.getString("projects", null)
            ?: return emptyList()

        return runCatching { decode(raw) }
            .getOrDefault(emptyList())
    }

    fun delete(name: String) {
        val all = loadAll().filterNot { it.name == name }

        prefs.edit()
            .putString("projects", encode(all))
            .apply()
    }

    private fun encode(projects: List<SavedProject>): String {
        val arr = JSONArray()

        projects.forEach { p ->
            val obj = JSONObject()
                .put("name", p.name)
                .put("savedAtMs", p.savedAtMs)
                .put("boundary", encodePoints(p.boundary))
                .put("referenceBoundary", encodePoints(p.referenceBoundary))
                .put("settings", encodeSettings(p.settings))

            p.preferredStart?.let {
                obj.put("preferredStart", encodePoint(it))
            }

            p.plan?.let {
                obj.put("plan", encodePlan(it))
            }

            arr.put(obj)
        }

        return arr.toString()
    }

    private fun decode(raw: String): List<SavedProject> {
        val arr = JSONArray(raw)

        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val settings = decodeSettings(obj.getJSONObject("settings"))
            val boundary = decodePoints(obj.optJSONArray("boundary"))
            val reference = decodePoints(obj.optJSONArray("referenceBoundary"))
            val preferredStart = obj.optJSONObject("preferredStart")?.let(::decodePoint)
            val rawSavedPlan = obj.optJSONObject("plan")?.let(::decodePlan)
            val savedPlan = rawSavedPlan?.let {
                upgradePlanIfNeeded(
                    plan = it,
                    projectBoundary = boundary,
                    preferredStart = preferredStart
                )
            }

            SavedProject(
                name = obj.getString("name"),
                boundary = boundary,
                settings = settings,
                savedAtMs = obj.optLong("savedAtMs", 0L),
                referenceBoundary = reference,
                preferredStart = preferredStart,
                plan = savedPlan
            )
        }
    }

    /**
     * Projetos gravados antes do voo contínuo não possuem surveyLines/routeWaypoints.
     * Eles são regenerados silenciosamente ao abrir, usando o quadro e os parâmetros
     * salvos. A orientação anterior é preservada usando o primeiro ponto antigo como
     * preferência quando possível.
     */
    private fun upgradePlanIfNeeded(
        plan: MissionPlan,
        projectBoundary: List<LatLng>,
        preferredStart: LatLng?
    ): MissionPlan {
        if (plan.surveyLines.isNotEmpty()) {
            if (plan.routeWaypoints.isNotEmpty()) return plan

            val route = routePointsForLines(plan.surveyLines)
            val distance = GeoMath.polylineDistanceM(route)
            return plan.copy(
                routeWaypoints = route,
                stats = plan.stats.copy(
                    routeDistanceM = distance,
                    estimatedFlightSeconds = if (plan.settings.speedMs > 0.0) {
                        distance / plan.settings.speedMs
                    } else {
                        plan.stats.estimatedFlightSeconds
                    }
                )
            )
        }

        val boundary = plan.boundary.takeIf { it.size >= 3 }
            ?: projectBoundary.takeIf { it.size >= 3 }
            ?: return plan

        return runCatching {
            val regenerated = GridPlanner.plan(boundary, plan.settings)

            val orientationReference =
                preferredStart ?: plan.photoPoints.firstOrNull()

            GridPlanner.orientTowardStart(
                regenerated,
                orientationReference
            )
        }.getOrDefault(plan)
    }

    private fun routePointsForLines(lines: List<SurveyLine>): List<LatLng> {
        val result = mutableListOf<LatLng>()

        lines.forEach { line ->
            if (result.lastOrNull()?.let { GeoMath.distanceM(it, line.start) < 0.05 } != true) {
                result += line.start
            }
            if (result.lastOrNull()?.let { GeoMath.distanceM(it, line.end) < 0.05 } != true) {
                result += line.end
            }
        }

        return result
    }

    private fun encodePoint(point: LatLng): JSONObject {
        return JSONObject()
            .put("lat", point.lat)
            .put("lon", point.lon)
    }

    private fun decodePoint(obj: JSONObject): LatLng {
        return LatLng(
            obj.getDouble("lat"),
            obj.getDouble("lon")
        )
    }

    private fun encodePoints(points: List<LatLng>): JSONArray {
        val arr = JSONArray()
        points.forEach { arr.put(encodePoint(it)) }
        return arr
    }

    private fun decodePoints(arr: JSONArray?): List<LatLng> {
        if (arr == null) return emptyList()

        return (0 until arr.length()).map { index ->
            decodePoint(arr.getJSONObject(index))
        }
    }

    private fun encodeSurveyLines(lines: List<SurveyLine>): JSONArray {
        val arr = JSONArray()

        lines.forEach { line ->
            arr.put(
                JSONObject()
                    .put("start", encodePoint(line.start))
                    .put("end", encodePoint(line.end))
                    .put("photoStartIndex", line.photoStartIndex)
                    .put("photoEndIndex", line.photoEndIndex)
                    .put("photoSpacingM", line.photoSpacingM)
            )
        }

        return arr
    }

    private fun decodeSurveyLines(arr: JSONArray?): List<SurveyLine> {
        if (arr == null) return emptyList()

        return (0 until arr.length()).mapNotNull { index ->
            val obj = arr.optJSONObject(index) ?: return@mapNotNull null
            val start = obj.optJSONObject("start") ?: return@mapNotNull null
            val end = obj.optJSONObject("end") ?: return@mapNotNull null

            val photoStart = obj.optInt("photoStartIndex", -1)
            val photoEnd = obj.optInt("photoEndIndex", -1)
            if (photoStart < 0 || photoEnd < photoStart) return@mapNotNull null

            SurveyLine(
                start = decodePoint(start),
                end = decodePoint(end),
                photoStartIndex = photoStart,
                photoEndIndex = photoEnd,
                photoSpacingM = obj.optDouble("photoSpacingM", 1.0).coerceAtLeast(0.5)
            )
        }
    }

    private fun encodeSettings(s: MissionSettings): JSONObject {
        return JSONObject()
            .put("altitudeM", s.altitudeM)
            .put("speedMs", s.speedMs)
            .put("frontOverlapPct", s.frontOverlapPct)
            .put("sideOverlapPct", s.sideOverlapPct)
            .put("bearingDeg", s.bearingDeg)
            .put("autoBearing", s.autoBearing)
            .put("crossHatch", s.crossHatch)
            .put("gimbalPitchDeg", s.gimbalPitchDeg)
            .put("maxWaypointsPerMission", s.maxWaypointsPerMission)
            .put("droneEnumValue", s.droneEnumValue)
            .put("finishAction", s.finishAction)
            .put("rcLostAction", s.rcLostAction)
    }

    private fun decodeSettings(s: JSONObject): MissionSettings {
        return MissionSettings(
            altitudeM = s.optDouble("altitudeM", 60.0),
            speedMs = s.optDouble("speedMs", 5.0),
            frontOverlapPct = s.optDouble("frontOverlapPct", 80.0),
            sideOverlapPct = s.optDouble("sideOverlapPct", 70.0),
            bearingDeg = s.optDouble("bearingDeg", 0.0),
            autoBearing = s.optBoolean("autoBearing", true),
            crossHatch = s.optBoolean("crossHatch", false),
            gimbalPitchDeg = s.optDouble("gimbalPitchDeg", -90.0),
            maxWaypointsPerMission = s.optInt("maxWaypointsPerMission", 190),
            droneEnumValue = s.optInt("droneEnumValue", 68),
            finishAction = s.optString("finishAction", "goHome"),
            rcLostAction = s.optString("rcLostAction", "goBack")
        )
    }

    private fun encodeStats(s: MissionStats): JSONObject {
        return JSONObject()
            .put("areaM2", s.areaM2)
            .put("gsdCmPx", s.gsdCmPx)
            .put("groundWidthM", s.groundWidthM)
            .put("groundHeightM", s.groundHeightM)
            .put("lineSpacingM", s.lineSpacingM)
            .put("photoSpacingM", s.photoSpacingM)
            .put("routeDistanceM", s.routeDistanceM)
            .put("estimatedFlightSeconds", s.estimatedFlightSeconds)
            .put("photoCount", s.photoCount)
            .put("flightLineCount", s.flightLineCount)
            .put("partCount", s.partCount)
            .put("effectiveBearingDeg", s.effectiveBearingDeg)
    }

    private fun decodeStats(s: JSONObject): MissionStats {
        return MissionStats(
            areaM2 = s.optDouble("areaM2", 0.0),
            gsdCmPx = s.optDouble("gsdCmPx", 0.0),
            groundWidthM = s.optDouble("groundWidthM", 0.0),
            groundHeightM = s.optDouble("groundHeightM", 0.0),
            lineSpacingM = s.optDouble("lineSpacingM", 0.0),
            photoSpacingM = s.optDouble("photoSpacingM", 0.0),
            routeDistanceM = s.optDouble("routeDistanceM", 0.0),
            estimatedFlightSeconds = s.optDouble("estimatedFlightSeconds", 0.0),
            photoCount = s.optInt("photoCount", 0),
            flightLineCount = s.optInt("flightLineCount", 0),
            partCount = s.optInt("partCount", 1),
            effectiveBearingDeg = s.optDouble("effectiveBearingDeg", 0.0)
        )
    }

    private fun encodePlan(plan: MissionPlan): JSONObject {
        val parts = JSONArray()

        plan.parts.forEach {
            parts.put(encodePoints(it))
        }

        return JSONObject()
            .put("boundary", encodePoints(plan.boundary))
            // `waypoints` permanece por compatibilidade: são os photoPoints.
            .put("waypoints", encodePoints(plan.photoPoints))
            .put("routeWaypoints", encodePoints(plan.routeWaypoints))
            .put("parts", parts)
            .put("settings", encodeSettings(plan.settings))
            .put("stats", encodeStats(plan.stats))
            .put("surveyLines", encodeSurveyLines(plan.surveyLines))
    }

    private fun decodePlan(obj: JSONObject): MissionPlan {
        val partsJson = obj.optJSONArray("parts") ?: JSONArray()
        val parts = (0 until partsJson.length()).map { index ->
            decodePoints(partsJson.optJSONArray(index))
        }

        return MissionPlan(
            boundary = decodePoints(obj.optJSONArray("boundary")),
            waypoints = decodePoints(obj.optJSONArray("waypoints")),
            parts = parts,
            settings = decodeSettings(obj.getJSONObject("settings")),
            stats = decodeStats(obj.getJSONObject("stats")),
            surveyLines = decodeSurveyLines(obj.optJSONArray("surveyLines")),
            routeWaypoints = decodePoints(obj.optJSONArray("routeWaypoints"))
        )
    }
}
