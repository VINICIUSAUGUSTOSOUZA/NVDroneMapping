import com.nv.dronemapping.dji.KmzExporter
import com.nv.dronemapping.geometry.GridPlanner
import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.MissionSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos

private fun rect(centerLat: Double, centerLon: Double, widthM: Double, heightM: Double): List<LatLng> {
    val latDeg = heightM / 111_320.0 / 2.0
    val lonDeg = widthM / (111_320.0 * cos(Math.toRadians(centerLat))) / 2.0
    return listOf(
        LatLng(centerLat-latDeg, centerLon-lonDeg),
        LatLng(centerLat-latDeg, centerLon+lonDeg),
        LatLng(centerLat+latDeg, centerLon+lonDeg),
        LatLng(centerLat+latDeg, centerLon-lonDeg),
    )
}

private fun assertThat(v: Boolean, msg: String) { if (!v) error(msg) }

fun main() {
    val base = MissionSettings(altitudeM=60.0, speedMs=5.0, frontOverlapPct=80.0, sideOverlapPct=70.0)

    val normal = GridPlanner.plan(rect(-26.1, -48.62, 120.0, 80.0), base)
    println("normal: photos=${normal.stats.photoCount} lines=${normal.stats.flightLineCount} area=${normal.stats.areaM2.toInt()} gsd=${"%.2f".format(normal.stats.gsdCmPx)}")
    assertThat(normal.waypoints.size >= 4, "Normal rectangle should produce waypoints")
    assertThat(normal.stats.areaM2 in 9_000.0..11_000.0, "Area should be close to 9600 m2")

    val narrow = GridPlanner.plan(rect(-26.1, -48.62, 8.0, 100.0), base)
    println("narrow: photos=${narrow.stats.photoCount} lines=${narrow.stats.flightLineCount}")
    assertThat(narrow.waypoints.size >= 2, "Narrow polygon must still produce a centered flight line")
    assertThat(narrow.stats.flightLineCount >= 1, "Narrow polygon must have at least one flight line")

    val dense = GridPlanner.plan(rect(-26.1, -48.62, 600.0, 600.0), base.copy(altitudeM=30.0, frontOverlapPct=90.0, sideOverlapPct=90.0))
    println("dense: photos=${dense.stats.photoCount} parts=${dense.parts.size} maxPart=${dense.parts.maxOf { it.size }}")
    assertThat(dense.parts.size > 1, "Dense mission should split")
    assertThat(dense.parts.all { it.size <= 190 }, "Every part must respect waypoint cap")

    val out = ByteArrayOutputStream()
    KmzExporter.writeKmz(normal, 0, "Smoke Test", out)
    val entries = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
        var e = zip.nextEntry
        while (e != null) {
            if (!e.isDirectory) entries[e.name] = zip.readBytes()
            zip.closeEntry(); e = zip.nextEntry
        }
    }
    assertThat(entries.keys == setOf("wpmz/template.kml", "wpmz/waylines.wpml"), "KMZ must have DJI wpmz structure")
    val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    entries.forEach { (name, bytes) ->
        dbf.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        println("xml ok: $name (${bytes.size} bytes)")
    }
    val waylines = entries.getValue("wpmz/waylines.wpml").toString(Charsets.UTF_8)
    val photoActions = Regex("<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>").findAll(waylines).count()
    assertThat(photoActions == normal.parts[0].size, "Full-auto export must have one photo action per waypoint")
    assertThat(waylines.contains("toPointAndStopWithDiscontinuityCurvature"), "Straight stop mode missing")
    assertThat(waylines.contains("<wpml:droneEnumValue>68</wpml:droneEnumValue>"), "Mini 5 Pro consumer enum default missing")
    println("KMZ full-auto: ${normal.parts[0].size} waypoints / $photoActions takePhoto actions")
    println("ALL CORE SMOKE TESTS PASSED")
}
