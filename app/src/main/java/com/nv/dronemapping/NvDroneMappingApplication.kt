package com.nv.dronemapping

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.util.Locale
import kotlin.math.pow

/**
 * Política isolada do mapa de satélite.
 *
 * Até o zoom 18 usamos os tiles normais do Esri World Imagery, que são rápidos.
 * Nos níveis mais próximos usamos o endpoint /export do mesmo MapServer. O export
 * renderiza a melhor imagem disponível para o bbox solicitado e evita depender de
 * um tile nativo de alta resolução que pode ser a imagem "Map data not yet available".
 *
 * O nome da fonte é novo para não reutilizar o cache das tentativas anteriores.
 * Nenhuma regra de missão, rota, fotos, projetos ou exportação do NV Mapping é alterada.
 */
class NvDroneMappingApplication : Application(), Application.ActivityLifecycleCallbacks {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is MainActivity) return

        activity.window.decorView.post {
            installSatellitePolicy(activity)
        }
    }

    private fun installSatellitePolicy(activity: MainActivity) {
        val map = activity.findViewById<MapView>(R.id.map) ?: return
        val mapTypeButton = activity.findViewById<View>(R.id.btnMapType) ?: return

        mapTypeButton.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                // O click normal do MainActivity troca MAP/SAT. Este post roda logo
                // depois e substitui somente a fonte SAT pela versão adaptativa.
                map.post {
                    applySatellitePolicy(map)
                }
            }
            false
        }
    }

    private fun applySatellitePolicy(map: MapView) {
        val currentSource = map.tileProvider.tileSource ?: return
        val sourceName = currentSource.name()

        if (
            sourceName == ESRI_SOURCE_NAME ||
            sourceName == OLD_SAFE_ESRI_SOURCE_NAME ||
            sourceName == OLD_FALLBACK_ESRI_SOURCE_NAME ||
            sourceName == ADAPTIVE_ESRI_SOURCE_NAME
        ) {
            val center = GeoPoint(
                map.mapCenter.latitude,
                map.mapCenter.longitude
            )
            val zoom = map.zoomLevelDouble.coerceAtMost(MAX_SATELLITE_ZOOM)

            map.setTileSource(adaptiveEsriTileSource)
            map.setMaxZoomLevel(MAX_SATELLITE_ZOOM)
            map.controller.setCenter(center)
            map.controller.setZoom(zoom)
            map.invalidate()
        } else {
            // Ao voltar ao mapa normal, deixa o próprio tile source definir o limite.
            map.setMaxZoomLevel(null)
        }
    }

    private val adaptiveEsriTileSource by lazy {
        object : OnlineTileSourceBase(
            ADAPTIVE_ESRI_SOURCE_NAME,
            0,
            MAX_SATELLITE_ZOOM.toInt(),
            TILE_SIZE,
            ".jpg",
            arrayOf(ESRI_SERVICE_ROOT)
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)

                if (z <= DIRECT_TILE_MAX_ZOOM) {
                    return "${getBaseUrl()}tile/$z/$y/$x"
                }

                val n = 2.0.pow(z.toDouble())
                val tileSpan = (2.0 * WEB_MERCATOR_HALF_WORLD) / n

                val minX = -WEB_MERCATOR_HALF_WORLD + x.toDouble() * tileSpan
                val maxX = minX + tileSpan
                val maxY = WEB_MERCATOR_HALF_WORLD - y.toDouble() * tileSpan
                val minY = maxY - tileSpan

                val bbox = String.format(
                    Locale.US,
                    "%.8f,%.8f,%.8f,%.8f",
                    minX,
                    minY,
                    maxX,
                    maxY
                )

                return buildString {
                    append(getBaseUrl())
                    append("export?bbox=")
                    append(bbox)
                    append("&bboxSR=3857")
                    append("&imageSR=3857")
                    append("&size=256,256")
                    append("&format=jpg")
                    append("&transparent=false")
                    append("&dpi=96")
                    append("&f=image")
                }
            }
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val ESRI_SOURCE_NAME = "EsriWorldImagery"
        private const val OLD_SAFE_ESRI_SOURCE_NAME = "EsriWorldImagerySafe"
        private const val OLD_FALLBACK_ESRI_SOURCE_NAME = "EsriWorldImageryFallbackV2"
        private const val ADAPTIVE_ESRI_SOURCE_NAME = "EsriWorldImageryAdaptiveV4"

        private const val DIRECT_TILE_MAX_ZOOM = 18
        private const val MAX_SATELLITE_ZOOM = 23.0
        private const val TILE_SIZE = 256
        private const val WEB_MERCATOR_HALF_WORLD = 20037508.342789244

        private const val ESRI_SERVICE_ROOT =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/"
    }
}
