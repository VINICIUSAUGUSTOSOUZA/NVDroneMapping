package com.nv.dronemapping.io

import android.content.ContentResolver
import android.net.Uri
import com.nv.dronemapping.model.LatLng
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object KmlImporter {
    fun importBoundary(resolver: ContentResolver, uri: Uri): List<LatLng> {
        val bytes = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não foi possível abrir o arquivo." }
            input.readBytes()
        }
        require(bytes.isNotEmpty()) { "Arquivo vazio." }
        val isZip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        return if (isZip) parseKmz(ByteArrayInputStream(bytes)) else parseKml(ByteArrayInputStream(bytes))
    }

    private fun parseKmz(input: InputStream): List<LatLng> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (entry.name.endsWith(".kml", true) || entry.name.endsWith(".wpml", true))) {
                    entries += entry.name to zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        require(entries.isNotEmpty()) { "KMZ sem arquivo KML/WPML." }
        val ordered = entries.sortedBy { if (it.first.contains("template.kml", true)) 0 else 1 }
        ordered.forEach { (_, bytes) ->
            runCatching { parseKml(ByteArrayInputStream(bytes)) }.getOrNull()?.let { pts ->
                if (pts.size >= 3) return pts
            }
        }
        throw IllegalArgumentException("Não encontrei um polígono utilizável no KMZ.")
    }

    private fun parseKml(input: InputStream): List<LatLng> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")

        var event = parser.eventType
        var insidePolygon = false
        var firstCoordinates: List<LatLng>? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "polygon" -> insidePolygon = true
                    "coordinates" -> {
                        val text = parser.nextText()
                        val pts = parseCoordinates(text)
                        if (pts.size >= 3) {
                            if (insidePolygon) return stripDuplicateClosingPoint(pts)
                            if (firstCoordinates == null) firstCoordinates = pts
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name.equals("Polygon", true)) {
                insidePolygon = false
            }
            event = parser.next()
        }
        return firstCoordinates?.let(::stripDuplicateClosingPoint)
            ?: throw IllegalArgumentException("Nenhum polígono encontrado no arquivo.")
    }

    private fun parseCoordinates(text: String): List<LatLng> = text
        .trim()
        .split(Regex("\\s+"))
        .mapNotNull { token ->
            val parts = token.split(',')
            if (parts.size < 2) return@mapNotNull null
            val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            LatLng(lat, lon)
        }

    private fun stripDuplicateClosingPoint(points: List<LatLng>): List<LatLng> {
        if (points.size > 3) {
            val first = points.first()
            val last = points.last()
            if (kotlin.math.abs(first.lat - last.lat) < 1e-9 && kotlin.math.abs(first.lon - last.lon) < 1e-9) {
                return points.dropLast(1)
            }
        }
        return points
    }
}
