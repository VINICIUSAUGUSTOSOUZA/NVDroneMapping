package com.nv.dronemapping.io

import com.nv.dronemapping.model.LatLng
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object DxfImporter {

    enum class Crs(val label: String) {
        LAT_LON("Latitude / Longitude"),
        SIRGAS_2000_UTM_22S("SIRGAS 2000 / UTM 22S"),
        SIRGAS_2000_UTM_23S("SIRGAS 2000 / UTM 23S")
    }

    data class DxfPoint(
        val x: Double,
        val y: Double
    )

    data class DxfPolyline(
        val name: String,
        val layer: String,
        val points: List<DxfPoint>,
        val closed: Boolean
    )

    private data class PairCode(
        val code: Int,
        val value: String
    )

    fun readPolylines(
        input: InputStream
    ): List<DxfPolyline> {

        val text =
            input
                .bufferedReader(
                    Charsets.UTF_8
                )
                .use {
                    it.readText()
                }

        require(
            !text.startsWith(
                "AutoCAD Binary DXF"
            )
        ) {
            "DXF binário não é suportado. Salve o arquivo como DXF ASCII e tente novamente."
        }

        val rawLines =
            text.lineSequence()
                .toList()

        require(
            rawLines.size >= 4
        ) {
            "Arquivo DXF vazio ou inválido."
        }

        val pairs =
            mutableListOf<PairCode>()

        var i = 0

        while (i + 1 < rawLines.size) {

            val code =
                rawLines[i]
                    .trim()
                    .toIntOrNull()

            if (code != null) {

                pairs +=
                    PairCode(
                        code,
                        rawLines[i + 1].trim()
                    )
            }

            i += 2
        }

        val result =
            mutableListOf<DxfPolyline>()

        var index = 0
        var lwCount = 0
        var polyCount = 0

        while (index < pairs.size) {

            val pair =
                pairs[index]

            if (
                pair.code == 0 &&
                pair.value.equals(
                    "LWPOLYLINE",
                    true
                )
            ) {

                val end =
                    findNextEntity(
                        pairs,
                        index + 1
                    )

                val entity =
                    pairs.subList(
                        index + 1,
                        end
                    )

                val layer =
                    entity
                        .firstOrNull {
                            it.code == 8
                        }
                        ?.value
                        .orEmpty()
                        .ifBlank {
                            "0"
                        }

                val flags =
                    entity
                        .firstOrNull {
                            it.code == 70
                        }
                        ?.value
                        ?.toIntOrNull()
                        ?: 0

                val points =
                    mutableListOf<DxfPoint>()

                var pendingX: Double? =
                    null

                entity.forEach { item ->

                    when (item.code) {

                        10 -> {

                            pendingX =
                                item.value
                                    .toDoubleOrNull()
                        }

                        20 -> {

                            val x =
                                pendingX

                            val y =
                                item.value
                                    .toDoubleOrNull()

                            if (
                                x != null &&
                                y != null
                            ) {

                                points +=
                                    DxfPoint(
                                        x,
                                        y
                                    )

                                pendingX =
                                    null
                            }
                        }
                    }
                }

                val cleaned =
                    cleanPoints(
                        points
                    )

                if (cleaned.size >= 3) {

                    lwCount++

                    result +=
                        DxfPolyline(
                            name =
                                "LWPOLYLINE $lwCount",
                            layer =
                                layer,
                            points =
                                cleaned,
                            closed =
                                flags and 1 == 1 ||
                                    isClosed(points)
                        )
                }

                index = end
                continue
            }

            if (
                pair.code == 0 &&
                pair.value.equals(
                    "POLYLINE",
                    true
                )
            ) {

                val headerEnd =
                    findNextEntity(
                        pairs,
                        index + 1
                    )

                val header =
                    pairs.subList(
                        index + 1,
                        headerEnd
                    )

                val layer =
                    header
                        .firstOrNull {
                            it.code == 8
                        }
                        ?.value
                        .orEmpty()
                        .ifBlank {
                            "0"
                        }

                val flags =
                    header
                        .firstOrNull {
                            it.code == 70
                        }
                        ?.value
                        ?.toIntOrNull()
                        ?: 0

                val points =
                    mutableListOf<DxfPoint>()

                var cursor =
                    headerEnd

                while (cursor < pairs.size) {

                    val e =
                        pairs[cursor]

                    if (e.code != 0) {
                        cursor++
                        continue
                    }

                    if (
                        e.value.equals(
                            "SEQEND",
                            true
                        )
                    ) {

                        cursor =
                            findNextEntity(
                                pairs,
                                cursor + 1
                            )

                        break
                    }

                    if (
                        !e.value.equals(
                            "VERTEX",
                            true
                        )
                    ) {
                        break
                    }

                    val vertexEnd =
                        findNextEntity(
                            pairs,
                            cursor + 1
                        )

                    val vertex =
                        pairs.subList(
                            cursor + 1,
                            vertexEnd
                        )

                    val x =
                        vertex
                            .firstOrNull {
                                it.code == 10
                            }
                            ?.value
                            ?.toDoubleOrNull()

                    val y =
                        vertex
                            .firstOrNull {
                                it.code == 20
                            }
                            ?.value
                            ?.toDoubleOrNull()

                    if (
                        x != null &&
                        y != null
                    ) {

                        points +=
                            DxfPoint(
                                x,
                                y
                            )
                    }

                    cursor =
                        vertexEnd
                }

                val cleaned =
                    cleanPoints(
                        points
                    )

                if (cleaned.size >= 3) {

                    polyCount++

                    result +=
                        DxfPolyline(
                            name =
                                "POLYLINE $polyCount",
                            layer =
                                layer,
                            points =
                                cleaned,
                            closed =
                                flags and 1 == 1 ||
                                    isClosed(points)
                        )
                }

                index =
                    cursor

                continue
            }

            index++
        }

        return result.sortedWith(

            compareByDescending<DxfPolyline> {
                it.closed
            }
                .thenBy {
                    it.layer.lowercase()
                }
                .thenBy {
                    it.name
                }
        )
    }

    fun toLatLng(
        polyline: DxfPolyline,
        crs: Crs
    ): List<LatLng> {

        val converted =
            polyline.points.map { point ->

                when (crs) {

                    Crs.LAT_LON -> {

                        require(
                            point.y in -90.0..90.0 &&
                                point.x in -180.0..180.0
                        ) {
                            "As coordenadas não parecem estar em latitude/longitude."
                        }

                        LatLng(
                            point.y,
                            point.x
                        )
                    }

                    Crs.SIRGAS_2000_UTM_22S ->

                        utmToLatLng(
                            point.x,
                            point.y,
                            22,
                            true
                        )

                    Crs.SIRGAS_2000_UTM_23S ->

                        utmToLatLng(
                            point.x,
                            point.y,
                            23,
                            true
                        )
                }
            }

        require(
            converted.size >= 3
        ) {
            "A polilinha selecionada não possui vértices suficientes."
        }

        return converted
    }

    private fun findNextEntity(
        pairs: List<PairCode>,
        start: Int
    ): Int {

        var i =
            start

        while (i < pairs.size) {

            if (pairs[i].code == 0) {
                return i
            }

            i++
        }

        return pairs.size
    }

    private fun cleanPoints(
        points: List<DxfPoint>
    ): List<DxfPoint> {

        val out =
            mutableListOf<DxfPoint>()

        points.forEach { p ->

            if (
                out.lastOrNull()?.let {
                    samePoint(
                        it,
                        p
                    )
                } != true
            ) {

                out += p
            }
        }

        if (
            out.size > 1 &&
            samePoint(
                out.first(),
                out.last()
            )
        ) {

            out.removeAt(
                out.lastIndex
            )
        }

        return out
    }

    private fun isClosed(
        points: List<DxfPoint>
    ): Boolean {

        return points.size > 2 &&
            samePoint(
                points.first(),
                points.last()
            )
    }

    private fun samePoint(
        a: DxfPoint,
        b: DxfPoint
    ): Boolean {

        return abs(
            a.x - b.x
        ) < 1e-7 &&
            abs(
                a.y - b.y
            ) < 1e-7
    }

    private fun utmToLatLng(
        easting: Double,
        northing: Double,
        zone: Int,
        southernHemisphere: Boolean
    ): LatLng {

        require(
            zone in 1..60
        ) {
            "Fuso UTM inválido."
        }

        require(
            easting in 100_000.0..900_000.0
        ) {
            "Coordenada Este UTM fora do intervalo esperado."
        }

        require(
            northing in 0.0..10_000_000.0
        ) {
            "Coordenada Norte UTM fora do intervalo esperado."
        }

        val a =
            6378137.0

        val inverseFlattening =
            298.257222101

        val f =
            1.0 /
                inverseFlattening

        val e2 =
            f * (
                2.0 - f
                )

        val e1 =
            (
                1.0 -
                    sqrt(
                        1.0 - e2
                    )
                ) /
                (
                    1.0 +
                        sqrt(
                            1.0 - e2
                        )
                    )

        val k0 =
            0.9996

        val x =
            easting -
                500_000.0

        var y =
            northing

        if (southernHemisphere) {
            y -= 10_000_000.0
        }

        val m =
            y / k0

        val mu =
            m /
                (
                    a *
                        (
                            1.0 -
                                e2 / 4.0 -
                                3.0 * e2.pow(2) / 64.0 -
                                5.0 * e2.pow(3) / 256.0
                            )
                    )

        val j1 =
            3.0 * e1 / 2.0 -
                27.0 * e1.pow(3) / 32.0

        val j2 =
            21.0 * e1.pow(2) / 16.0 -
                55.0 * e1.pow(4) / 32.0

        val j3 =
            151.0 * e1.pow(3) / 96.0

        val j4 =
            1097.0 * e1.pow(4) / 512.0

        val fp =
            mu +
                j1 * sin(2.0 * mu) +
                j2 * sin(4.0 * mu) +
                j3 * sin(6.0 * mu) +
                j4 * sin(8.0 * mu)

        val ePrime2 =
            e2 /
                (
                    1.0 - e2
                    )

        val sinFp =
            sin(fp)

        val cosFp =
            cos(fp)

        val tanFp =
            tan(fp)

        val c1 =
            ePrime2 *
                cosFp.pow(2)

        val t1 =
            tanFp.pow(2)

        val n1 =
            a /
                sqrt(
                    1.0 -
                        e2 * sinFp.pow(2)
                )

        val r1 =
            a *
                (
                    1.0 - e2
                    ) /
                (
                    1.0 -
                        e2 * sinFp.pow(2)
                    ).pow(1.5)

        val d =
            x /
                (
                    n1 * k0
                    )

        val latRad =
            fp -
                (
                    n1 *
                        tanFp /
                        r1
                    ) *
                (
                    d.pow(2) / 2.0 -
                        (
                            5.0 +
                                3.0 * t1 +
                                10.0 * c1 -
                                4.0 * c1.pow(2) -
                                9.0 * ePrime2
                            ) *
                        d.pow(4) /
                        24.0 +
                        (
                            61.0 +
                                90.0 * t1 +
                                298.0 * c1 +
                                45.0 * t1.pow(2) -
                                252.0 * ePrime2 -
                                3.0 * c1.pow(2)
                            ) *
                        d.pow(6) /
                        720.0
                    )

        val lonOriginDeg =
            (
                zone - 1
                ) *
                6.0 -
                180.0 +
                3.0

        val lonRad =
            (
                d -
                    (
                        1.0 +
                            2.0 * t1 +
                            c1
                        ) *
                    d.pow(3) /
                    6.0 +
                    (
                        5.0 -
                            2.0 * c1 +
                            28.0 * t1 -
                            3.0 * c1.pow(2) +
                            8.0 * ePrime2 +
                            24.0 * t1.pow(2)
                        ) *
                    d.pow(5) /
                    120.0
                ) /
                cosFp

        val lat =
            latRad *
                180.0 /
                PI

        val lon =
            lonOriginDeg +
                lonRad *
                180.0 /
                PI

        require(
            lat in -90.0..90.0 &&
                lon in -180.0..180.0
        ) {
            "Falha ao converter coordenadas UTM."
        }

        return LatLng(
            lat,
            lon
        )
    }
}
