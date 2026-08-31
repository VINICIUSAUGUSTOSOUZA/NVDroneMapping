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
 * Isolated map compatibility fix.
 *
 * Esri World Imagery can return a JPEG saying "Map data not yet available"
 * when a location has no imagery for the closest LODs. For field work it is
 * better to keep the last reliable imagery visible and allow osmdroid to
 * over-zoom/rescale it than to display those placeholder tiles.
 *
 * This class does not change mission planning, routes, photo triggers,
 * projects or exports.
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
                // MainActivity handles the normal click first; this runs just after it
                // and only adjusts the satellite tile behavior.
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

        if (sourceName == ESRI_SOURCE_NAME) {
            val center = GeoPoint(
                map.mapCenter.latitude,
                map.mapCenter.longitude
            )

            // Keep the map capable of close visual zoom, but stop network tile
            // requests at the reliable imagery level. osmdroid then enlarges the
            // cached parent tiles instead of showing Esri's no-data JPEG.
            map.setMaxZoomLevel(VISUAL_MAX_ZOOM)
            map.setTileSource(safeEsriTileSource)

            if (map.zoomLevelDouble > RELIABLE_ESRI_ZOOM) {
                map.controller.setZoom(RELIABLE_ESRI_ZOOM.toDouble())
            }
            map.controller.setCenter(center)
            map.invalidate()
        } else if (sourceName != SAFE_ESRI_SOURCE_NAME) {
            // Restore the tile provider's own normal limit when leaving SAT mode.
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
        private const val VISUAL_MAX_ZOOM = 23.0
        private const val ESRI_BASE_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
    }
}
