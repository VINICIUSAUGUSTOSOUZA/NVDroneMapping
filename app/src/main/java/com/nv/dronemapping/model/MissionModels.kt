package com.nv.dronemapping.model

data class LatLng(
    val lat: Double,
    val lon: Double
)

data class MissionSettings(
    val altitudeM: Double = 60.0,
    val speedMs: Double = 5.0,
    val frontOverlapPct: Double = 80.0,
    val sideOverlapPct: Double = 70.0,
    val bearingDeg: Double = 0.0,
    val autoBearing: Boolean = true,
    val crossHatch: Boolean = false,
    val gimbalPitchDeg: Double = -90.0,
    val maxWaypointsPerMission: Int = 190,
    val droneEnumValue: Int = 68,
    val finishAction: String = "goHome",
    val rcLostAction: String = "goBack"
)

data class CameraModel(
    val name: String = "DJI Mini 5 Pro - câmera principal",
    val diagonalFovDeg: Double = 84.0,
    val imageWidthPx: Int = 8192,
    val imageHeightPx: Int = 6144,
    val aspectWidth: Double = 4.0,
    val aspectHeight: Double = 3.0
)

/**
 * Uma faixa efetiva de levantamento.
 *
 * Os índices apontam para MissionPlan.photoPoints. A faixa guarda a geometria
 * usada pelo exportador DJI e o espaçamento real entre disparos naquela linha.
 */
data class SurveyLine(
    val start: LatLng,
    val end: LatLng,
    val photoStartIndex: Int,
    val photoEndIndex: Int,
    val photoSpacingM: Double
)

data class MissionStats(
    val areaM2: Double,
    val gsdCmPx: Double,
    val groundWidthM: Double,
    val groundHeightM: Double,
    val lineSpacingM: Double,
    val photoSpacingM: Double,
    val routeDistanceM: Double,
    val estimatedFlightSeconds: Double,
    val photoCount: Int,
    val flightLineCount: Int,
    val partCount: Int,
    val effectiveBearingDeg: Double
)

data class MissionPlan(
    val boundary: List<LatLng>,
    /**
     * Pontos previstos de disparo. O nome `waypoints` é mantido no armazenamento
     * por retrocompatibilidade com projetos antigos; não representa mais os
     * waypoints enviados ao DJI.
     */
    val waypoints: List<LatLng>,
    /** Partes por bateria/limite, expressas como subconjuntos dos pontos de foto. */
    val parts: List<List<LatLng>>,
    val settings: MissionSettings,
    val stats: MissionStats,
    /** Faixas nas quais a câmera deve fotografar por distância. */
    val surveyLines: List<SurveyLine> = emptyList(),
    /** Geometria enxuta que representa o trajeto real do DJI. */
    val routeWaypoints: List<LatLng> = emptyList()
) {
    /** Nome explícito usado pelo código novo. */
    val photoPoints: List<LatLng>
        get() = waypoints
}

data class SavedProject(
    val name: String,
    val boundary: List<LatLng>,
    val settings: MissionSettings,
    val savedAtMs: Long,
    val referenceBoundary: List<LatLng> = emptyList(),
    val preferredStart: LatLng? = null,
    val plan: MissionPlan? = null
)
