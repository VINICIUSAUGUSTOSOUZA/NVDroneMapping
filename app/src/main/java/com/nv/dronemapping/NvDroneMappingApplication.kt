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

/**
 * Correção isolada para o mapa de satélite.
 *
 * O Esri World Imagery pode devolver a própria imagem "Map data not yet available"
 * em níveis de zoom onde a região não possui tile real. O osmdroid já possui um
 * MapTileApproximater capaz de ampliar um tile válido de um nível anterior.
 *
 * A fonte online atende somente até um nível confiável (18), enquanto o MapView
 * continua permitindo aproximação visual até 23. Ao ativar SAT já muito aproximado,
 * o mapa volta uma vez ao nível 18 para garantir que o tile pai real seja carregado.
 * Depois disso o usuário pode aproximar novamente e o osmdroid amplia o tile válido,
 * sem pedir ao servidor os níveis inexistentes.
 *
 * Um nome de fonte novo evita reutilizar tiles inválidos das tentativas anteriores.
 *
 * Não altera planejamento, rota, fotos, projetos ou exportação.
 */
class NvDroneMappingApplication : Application(), Application.ActivityLifecycleCallbacks {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is MainActivity) return

        activity.window.decorView.post {
            installSatelliteZoomFix(activity)
        }
    }

    private fun installSatelliteZoomFix(activity: MainActivity) {
        val map = activity.findViewById<MapView>(R.id.map) ?: return
        val mapTypeButton = activity.findViewById<View>(R.id.btnMapType) ?: return

        mapTypeButton.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                map.post {
                    applySatelliteZoomPolicy(map)
                }
            }
            false
        }
    }

    private fun applySatelliteZoomPolicy(map: MapView) {
        val currentSource = map.tileProvider.tileSource ?: return
        val sourceName = currentSource.name()

        if (
            sourceName == ESRI_SOURCE_NAME ||
            sourceName == OLD_SAFE_ESRI_SOURCE_NAME ||
            sourceName == FALLBACK_ESRI_SOURCE_NAME
        ) {
            val center = GeoPoint(
                map.mapCenter.latitude,
                map.mapCenter.longitude
            )
            val requestedZoom = map.zoomLevelDouble.coerceAtMost(VISUAL_MAX_ZOOM)

            // O downloader da fonte online para no nível 18. Acima disso, o
            // MapTileApproximater do provider padrão do osmdroid amplia um tile pai.
            map.setTileSource(fallbackEsriTileSource)
            map.setMaxZoomLevel(VISUAL_MAX_ZOOM)

            // Se SAT foi ativado em um zoom maior, primeiro garante um tile real no
            // nível 18. Depois o usuário pode continuar aproximando normalmente.
            val initialSatelliteZoom =
                requestedZoom.coerceAtMost(RELIABLE_ESRI_ZOOM.toDouble())

            map.controller.setCenter(center)
            map.controller.setZoom(initialSatelliteZoom)
            map.invalidate()
        } else {
            // Ao voltar para o mapa normal, remove a regra especial do SAT.
            map.setMaxZoomLevel(null)
        }
    }

    private val fallbackEsriTileSource by lazy {
        object : OnlineTileSourceBase(
            FALLBACK_ESRI_SOURCE_NAME,
            0,
            RELIABLE_ESRI_ZOOM,
            256,
            ".jpg",
            arrayOf(ESRI_BASE_URL)
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return "${getBaseUrl()}$z/$y/$x$mImageFilenameEnding"
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
        private const val FALLBACK_ESRI_SOURCE_NAME = "EsriWorldImageryFallbackV2"
        private const val RELIABLE_ESRI_ZOOM = 18
        private const val VISUAL_MAX_ZOOM = 23.0
        private const val ESRI_BASE_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
    }
}
