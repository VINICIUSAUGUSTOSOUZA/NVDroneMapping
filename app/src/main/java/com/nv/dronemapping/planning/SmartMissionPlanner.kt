package com.nv.dronemapping.planning

import com.nv.dronemapping.geometry.GeoMath
import com.nv.dronemapping.model.CameraModel
import com.nv.dronemapping.model.LatLng
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Planejamento complementar, isolado da geração geométrica da grade.
 *
 * O GridPlanner continua gerando a mesma rota. Este módulo apenas:
 * 1) converte um GSD desejado em altura para a câmera cadastrada;
 * 2) divide uma rota pronta em partes executáveis por bateria;
 * 3) inclui ida desde o ponto de partida, levantamento, fotos, retorno e margem.
 */
object SmartMissionPlanner {

    data class Options(
        val autoAltitudeFromGsd: Boolean = false,
        val targetGsdCmPx: Double = 1.5,
        val nominalBatteryMinutes: Double = 30.0,
        val reservePct: Double = 25.0,
        val overlapPhotos: Int = 5
    )

    data class PartInfo(
        val partNumber: Int,
        val startPhotoNumber: Int,
        val endPhotoNumber: Int,
        val waypointCount: Int,
        val outboundSeconds: Double,
        val surveySeconds: Double,
        val returnSeconds: Double,
        val estimatedSeconds: Double,
        val exceedsUsableTime: Boolean
    )

    data class BatteryPlan(
        val parts: List<List<LatLng>>,
        val infos: List<PartInfo>,
        val nominalSeconds: Double,
        val usableSeconds: Double,
        val reservePct: Double,
        val homeUsed: Boolean
    ) {
        val batteryCount: Int
            get() = parts.size
    }

    /**
     * Usa a mesma geometria de câmera adotada no GridPlanner atual.
     */
    fun altitudeForGsd(
        targetGsdCmPx: Double,
        camera: CameraModel = CameraModel()
    ): Double {
        require(targetGsdCmPx > 0.0) { "GSD deve ser maior que zero." }

        val diagTan = tan(Math.toRadians(camera.diagonalFovDeg / 2.0))
        val diag = hypot(camera.aspectWidth, camera.aspectHeight)
        val hTan = diagTan * camera.aspectWidth / diag
        val groundWidthAtOneMeter = 2.0 * hTan
        val gsdAtOneMeterCmPx = groundWidthAtOneMeter / camera.imageWidthPx * 100.0

        return targetGsdCmPx / gsdAtOneMeterCmPx
    }

    fun splitByBattery(
        waypoints: List<LatLng>,
        speedMs: Double,
        maxWaypointsPerMission: Int,
        options: Options,
        home: LatLng?
    ): BatteryPlan {
        require(waypoints.size >= 2) { "A rota precisa ter pelo menos dois pontos." }
        require(speedMs > 0.0) { "Velocidade inválida." }
        require(options.nominalBatteryMinutes >= 5.0) { "Autonomia de bateria inválida." }
        require(options.reservePct in 0.0..60.0) { "Reserva de bateria inválida." }

        val nominalSeconds = options.nominalBatteryMinutes * 60.0
        val usableSeconds = nominalSeconds * (1.0 - options.reservePct / 100.0)
        val maxPoints = maxWaypointsPerMission.coerceIn(20, 200)
        val repeatPhotos = options.overlapPhotos.coerceIn(0, 20)

        val parts = mutableListOf<List<LatLng>>()
        val infos = mutableListOf<PartInfo>()

        var start = 0
        var partNumber = 1
        var safety = 0

        while (start < waypoints.lastIndex && safety < 10_000) {
            safety++

            val maxEndByPoints = min(
                waypoints.lastIndex,
                start + maxPoints - 1
            )

            var bestEnd = min(start + 1, maxEndByPoints)
            var candidate = bestEnd
            var foundWithinTime = false

            while (candidate <= maxEndByPoints) {
                val candidatePoints = waypoints.subList(start, candidate + 1)
                val estimate = estimatePart(candidatePoints, speedMs, home)

                if (estimate.totalSeconds <= usableSeconds) {
                    bestEnd = candidate
                    foundWithinTime = true
                    candidate++
                } else {
                    break
                }
            }

            // Se até o menor trecho excede a janela útil, ainda produz uma parte
            // mínima e marca o alerta. Assim o usuário é avisado em vez de perder rota.
            if (!foundWithinTime) {
                bestEnd = min(start + 1, maxEndByPoints)
            }

            val part = waypoints.subList(start, bestEnd + 1).toList()
            val estimate = estimatePart(part, speedMs, home)

            parts += part
            infos += PartInfo(
                partNumber = partNumber,
                startPhotoNumber = start + 1,
                endPhotoNumber = bestEnd + 1,
                waypointCount = part.size,
                outboundSeconds = estimate.outboundSeconds,
                surveySeconds = estimate.surveySeconds,
                returnSeconds = estimate.returnSeconds,
                estimatedSeconds = estimate.totalSeconds,
                exceedsUsableTime = estimate.totalSeconds > usableSeconds
            )

            if (bestEnd >= waypoints.lastIndex) {
                break
            }

            // A próxima bateria reinicia algumas fotos antes do fim anterior.
            // Ex.: fim 300 e repetição 5 -> próxima inicia na foto 296.
            val nextStart = max(
                start + 1,
                bestEnd - repeatPhotos + 1
            )

            start = nextStart
            partNumber++
        }

        return BatteryPlan(
            parts = parts,
            infos = infos,
            nominalSeconds = nominalSeconds,
            usableSeconds = usableSeconds,
            reservePct = options.reservePct,
            homeUsed = home != null
        )
    }

    private data class Estimate(
        val outboundSeconds: Double,
        val surveySeconds: Double,
        val returnSeconds: Double,
        val totalSeconds: Double
    )

    private fun estimatePart(
        points: List<LatLng>,
        speedMs: Double,
        home: LatLng?
    ): Estimate {
        val outboundSeconds =
            if (home != null) GeoMath.distanceM(home, points.first()) / speedMs else 0.0

        val routeSeconds = GeoMath.polylineDistanceM(points) / speedMs

        // Mantém a mesma premissa já usada no app para parada/disparo por foto.
        val photoSeconds = points.size * PHOTO_ACTION_SECONDS

        val returnSeconds =
            if (home != null) GeoMath.distanceM(points.last(), home) / speedMs else 0.0

        val surveySeconds = routeSeconds + photoSeconds
        val total = outboundSeconds + surveySeconds + returnSeconds + OPERATION_OVERHEAD_SECONDS

        return Estimate(
            outboundSeconds = outboundSeconds,
            surveySeconds = surveySeconds,
            returnSeconds = returnSeconds,
            totalSeconds = total
        )
    }

    private const val PHOTO_ACTION_SECONDS = 1.5
    private const val OPERATION_OVERHEAD_SECONDS = 60.0
}
