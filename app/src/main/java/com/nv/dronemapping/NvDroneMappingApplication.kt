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
 * A política abaixo deixa a fonte online servir apenas até um nível confiável,
 * mas mantém o MapView apto a aproximar mais. Assim, acima do limite real da
 * fonte, o osmdroid usa a aproximação do tile pai em vez de pedir um tile cinza
 * ao servidor.
 *
 * Um nome de fonte novo é usado para não reutilizar tiles inválidos que possam
 * ter ficado gravados no cache das tentativas anteriores.
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
            val zoom = map.zoomLevelDouble.coerceAtMost(VISUAL_MAX_ZOOM)

            // A fonte online para no nível 18. Acima disso, o downloader deixa de
            // atender a requisição e o MapTileApproximater do provider padrão do
            // osmdroid amplia o melhor tile inferior existente no cache.
            map.setTileSource(fallbackEsriTileSource)
            map.setMaxZoomLevel(VISUAL_MAX_ZOOM)

            // Mantém o nível visual solicitado pelo usuário em vez de reduzi-lo a 18.
            map.controller.setCenter(center)
            map.controller.setZoom(zoom)
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
