package com.nv.dronemapping.storage

import android.content.Context
import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.MissionSettings
import com.nv.dronemapping.model.SavedProject
import org.json.JSONArray
import org.json.JSONObject

class ProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("nv_drone_projects", Context.MODE_PRIVATE)

    fun save(project: SavedProject) {
        val all = loadAll().toMutableList()
        all.removeAll { it.name.equals(project.name, true) }
        all.add(0, project)
        prefs.edit().putString("projects", encode(all.take(50))).apply()
    }

    fun loadAll(): List<SavedProject> {
        val raw = prefs.getString("projects", null) ?: return emptyList()
        return runCatching { decode(raw) }.getOrDefault(emptyList())
    }

    fun delete(name: String) {
        val all = loadAll().filterNot { it.name == name }
        prefs.edit().putString("projects", encode(all)).apply()
    }

    private fun encode(projects: List<SavedProject>): String {
        val arr = JSONArray()
        projects.forEach { p ->
            val boundary = JSONArray()
            p.boundary.forEach { pt ->
                boundary.put(JSONObject().put("lat", pt.lat).put("lon", pt.lon))
            }
            val s = p.settings
            val settings = JSONObject()
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

            arr.put(JSONObject()
                .put("name", p.name)
                .put("savedAtMs", p.savedAtMs)
                .put("boundary", boundary)
                .put("settings", settings))
        }
        return arr.toString()
    }

    private fun decode(raw: String): List<SavedProject> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val b = obj.getJSONArray("boundary")
            val boundary = (0 until b.length()).map { j ->
                val p = b.getJSONObject(j)
                LatLng(p.getDouble("lat"), p.getDouble("lon"))
            }
            val s = obj.getJSONObject("settings")
            val settings = MissionSettings(
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
            SavedProject(
                name = obj.getString("name"),
                boundary = boundary,
                settings = settings,
                savedAtMs = obj.optLong("savedAtMs", 0L)
            )
        }
    }
}
