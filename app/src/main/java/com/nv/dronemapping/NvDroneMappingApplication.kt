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
 * O Esri World Imagery pode retornar a própria imagem "Map data not yet available"
 * quando o usuário ultrapassa o nível de detalhe realmente disponível para a região.
 * Em vez de permitir esse estado inválido, o NV Mapping limita o modo SAT ao último
 * nível confiável e restaura o limite normal ao voltar para MAP.
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

        if (sourceName == ESRI_SOURCE_NAME || sourceName == SAFE_ESRI_SOURCE_NAME) {
            val center = GeoPoint(
                map.mapCenter.latitude,
                map.mapCenter.longitude
            )

            // Regra robusta: SAT não pode pedir tiles acima do nível que usamos
            // como confiável. Isso impede o mosaico "Map data not yet available".
            map.setTileSource(safeEsriTileSource)
            map.setMaxZoomLevel(RELIABLE_ESRI_ZOOM.toDouble())

            val targetZoom =
                map.zoomLevelDouble.coerceAtMost(RELIABLE_ESRI_ZOOM.toDouble())

            map.controller.setCenter(center)
            map.controller.setZoom(targetZoom)
            map.invalidate()
        } else {
            // Ao voltar ao mapa normal, remove o limite específico do satélite.
            map.setMaxZoomLevel(null)
        }
    }

    private val safeEsriTileSource by lazy {
        object : OnlineTileSourceBase(
            SAFE_ESRI_SOURCE_NAME,
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
        private const val SAFE_ESRI_SOURCE_NAME = "EsriWorldImagerySafe"
        private const val RELIABLE_ESRI_ZOOM = 18
        private const val ESRI_BASE_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
    }
}
