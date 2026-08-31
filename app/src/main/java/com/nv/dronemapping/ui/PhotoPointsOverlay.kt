package com.nv.dronemapping.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import com.nv.dronemapping.model.LatLng
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Desenha somente os pontos de disparo das fotos sobre o mapa.
 *
 * A camada é apenas visual: não altera rota, waypoints, estatísticas
 * nem o conteúdo exportado para o DJI.
 */
class PhotoPointsOverlay(
    points: List<LatLng>
) : Overlay() {

    private val photoPoints = points.toList()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 188, 212)
        style = Paint.Style.STROKE
    }

    override fun draw(
        canvas: Canvas,
        mapView: MapView,
        shadow: Boolean
    ) {
        if (shadow || photoPoints.isEmpty()) return

        val density = mapView.resources.displayMetrics.density
        val radius = (3.4f * density).coerceAtLeast(3.4f)
        outlinePaint.strokeWidth = (1.4f * density).coerceAtLeast(1.4f)

        val projection = mapView.projection
        val screenPoint = Point()

        photoPoints.forEach { point ->
            projection.toPixels(
                GeoPoint(point.lat, point.lon),
                screenPoint
            )

            canvas.drawCircle(
                screenPoint.x.toFloat(),
                screenPoint.y.toFloat(),
                radius,
                fillPaint
            )

            canvas.drawCircle(
                screenPoint.x.toFloat(),
                screenPoint.y.toFloat(),
                radius,
                outlinePaint
            )
        }
    }
}
