package com.nv.dronemapping.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.nv.dronemapping.MainActivity
import com.nv.dronemapping.R
import com.nv.dronemapping.model.MissionPlan
import com.nv.dronemapping.planning.SmartMissionPlanner
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.ceil

/**
 * Central visual da missão.
 *
 * Lê o estado exclusivamente através de MissionUiHost. Não usa reflection e não
 * substitui listeners da MainActivity. A Activity chama refreshNow() quando muda
 * o plano; o TextWatcher existe apenas como atualização visual complementar.
 */
class ProfessionalMissionController(
    private val activity: MainActivity,
    private val host: MissionUiHost
) {

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val professionalOverlays = mutableListOf<Overlay>()

    private var missionSummary: TextView? = null
    private var missionExportHelp: TextView? = null
    private var refreshGeneration = 0

    fun install() {
        relabelDjiAsMission()
        buildMissionWorkspace()
        installStatsObserver()
        installMapGestureGuard()
        refreshNow()
    }

    fun refreshNow() {
        scheduleRefresh(immediate = true)
    }

    /** Mantém cinco itens na barra: Projetos | Voo | Foto | Missão | Avançado. */
    private fun relabelDjiAsMission() {
        val root = activity.findViewById<View>(R.id.bottomNavigationContainer) ?: return

        fun visit(view: View) {
            if (view is TextView) {
                val value = view.text?.toString().orEmpty()
                if (value.endsWith("\nDJI") || value == "DJI") {
                    view.text = "🧭\nMissão"
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    visit(view.getChildAt(i))
                }
            }
        }

        visit(root)
    }

    private fun buildMissionWorkspace() {
        val panel = activity.findViewById<LinearLayout>(R.id.panelDji) ?: return
        if (panel.findViewWithTag<View>(MISSION_CARD_TAG) != null) return

        val title = TextView(activity).apply {
            tag = MISSION_CARD_TAG
            text = "MISSÃO DE VOO"
            textSize = 11.5f
            setTextColor(NAVY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }

        missionSummary = TextView(activity).apply {
            text = "Gere um plano de voo para ver o resumo completo da missão."
            textSize = 10.5f
            setTextColor(Color.rgb(35, 45, 55))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(245, 248, 251))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(210, 219, 229))
            }
            setOnClickListener { showMissionDetails() }
        }

        panel.addView(title, 0)
        panel.addView(
            missionSummary,
            1,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        )

        val exportButton = activity.findViewById<Button>(R.id.btnExport)
        val previewButton = activity.findViewById<Button>(R.id.btnPreviewKml)
        val shareButton = activity.findViewById<Button>(R.id.btnShare)
        val oldExportRow = exportButton?.parent as? ViewGroup

        val exportRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        listOfNotNull(exportButton, previewButton, shareButton).forEachIndexed { index, button ->
            (button.parent as? ViewGroup)?.removeView(button)
            button.minHeight = 0
            button.minWidth = 0
            button.textSize = if (index == 0) 8.5f else 8f
            exportRow.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    dp(38),
                    if (index == 0) 1.35f else 1f
                ).apply {
                    marginStart = if (index == 0) 0 else dp(3)
                }
            )
        }

        oldExportRow?.visibility = View.GONE
        panel.addView(exportRow, 2)

        missionExportHelp = TextView(activity).apply {
            textSize = 9.3f
            setTextColor(Color.rgb(80, 90, 100))
            setPadding(dp(4), dp(5), dp(4), dp(5))
            text = "A exportação será organizada aqui quando o plano estiver pronto."
        }
        panel.addView(missionExportHelp, 3)

        activity.findViewById<Button>(R.id.btnDjiGuide)?.apply {
            text = "COMO EXECUTAR NO DJI FLY"
            textSize = 8.5f
        }
    }

    private fun installStatsObserver() {
        val stats = activity.findViewById<TextView>(R.id.txtStats) ?: return
        stats.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    scheduleRefresh()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            }
        )
    }

    private fun scheduleRefresh(immediate: Boolean = false) {
        val generation = ++refreshGeneration
        val target = activity.findViewById<MapView>(R.id.map) ?: activity.window.decorView
        val action = {
            if (generation == refreshGeneration) {
                refreshMissionWorkspace()
                replaceBatteryGraphics()
            }
        }

        if (immediate) target.post(action) else target.postDelayed(action, 180L)
    }

    private fun refreshMissionWorkspace() {
        val plan = host.currentMissionPlan()
        val export = activity.findViewById<Button>(R.id.btnExport)

        if (plan == null) {
            missionSummary?.text =
                "Gere o plano de voo. Aqui aparecerão fotos, faixas, tempo, distância, baterias e partes DJI."
            missionExportHelp?.text =
                "Fluxo: planejar → revisar → exportar → conferir no DJI Fly → testar."
            export?.text = "EXPORTAR KMZ"
            return
        }

        val batteryPlan = calculateBatteryPlan(plan)
        val batteries = batteryPlan?.batteryCount ?: plan.parts.size.coerceAtLeast(1)
        val changes = (batteries - 1).coerceAtLeast(0)
        val minutes = ceil(plan.stats.estimatedFlightSeconds / 60.0).toInt()
        val altitudeMode = if (prefs.getBoolean("auto_gsd", false)) "AUTO GSD" else "MANUAL"

        missionSummary?.text = buildString {
            append("${plan.stats.photoCount} fotos • ${plan.surveyLines.size} faixas • ${formatDistance(plan.stats.routeDistanceM)} • ~${minutes} min\n")
            append(
                String.format(
                    Locale.getDefault(),
                    "Altura %.0f m (%s) • GSD %.2f cm/px • %d %s",
                    plan.settings.altitudeM,
                    altitudeMode,
                    plan.stats.gsdCmPx,
                    batteries,
                    if (batteries == 1) "bateria" else "baterias"
                )
            )
            if (changes > 0) {
                append("\n$changes ${if (changes == 1) "troca" else "trocas"}: ")
                append((1..changes).joinToString(" • ") { "B$it→B${it + 1}" })
            } else {
                append("\nA missão cabe em uma bateria pela configuração atual.")
            }
            if (batteryPlan != null && !batteryPlan.homeUsed) {
                append("\n⚠ Defina INÍCIO/Partida para calcular ida e retorno.")
            }
            append("\nToque para ver cada parte.")
        }

        export?.text =
            if (batteries > 1) "EXPORTAR $batteries PARTES" else "EXPORTAR KMZ"

        missionExportHelp?.text = if (batteries > 1) {
            "Cada parte corresponde a uma bateria. O NV Mapping prefere terminar no final de uma faixa e retoma com sobreposição configurada."
        } else {
            "Uma missão. As fotos são disparadas por distância durante as faixas; confira tudo no DJI Fly antes de voar."
        }
    }

    private fun showMissionDetails() {
        val plan = host.currentMissionPlan()
        if (plan == null) {
            AlertDialog.Builder(activity)
                .setTitle("Missão")
                .setMessage("Gere primeiro o plano de voo.")
                .setPositiveButton("ENTENDI", null)
                .show()
            return
        }

        val batteryPlan = calculateBatteryPlan(plan)
        val batteries = batteryPlan?.batteryCount ?: plan.parts.size.coerceAtLeast(1)

        val text = buildString {
            append("RESUMO GERAL\n")
            append("Área: ${formatArea(plan.stats.areaM2)}\n")
            append("Altura: ${formatNumber(plan.settings.altitudeM)} m\n")
            append(String.format(Locale.getDefault(), "GSD: %.2f cm/pixel\n", plan.stats.gsdCmPx))
            append("Fotos previstas: ${plan.stats.photoCount}\n")
            append("Faixas: ${plan.surveyLines.size}\n")
            append("Waypoints geométricos da rota: ${plan.routeWaypoints.size}\n")
            append("Distância: ${formatDistance(plan.stats.routeDistanceM)}\n")
            append("Baterias/partes: $batteries\n")

            if (batteryPlan != null) {
                append(String.format(Locale.getDefault(), "Reserva: %.0f%% • útil: %.1f min/bateria\n", batteryPlan.reservePct, batteryPlan.usableSeconds / 60.0))
                append("\nPARTES DO VOO\n")
                batteryPlan.infos.forEach { info ->
                    append(
                        String.format(
                            Locale.getDefault(),
                            "Parte %d — fotos %d a %d • %d WP DJI • ~%.1f min%s\n",
                            info.partNumber,
                            info.startPhotoNumber,
                            info.endPhotoNumber,
                            info.waypointCount,
                            info.estimatedSeconds / 60.0,
                            if (info.exceedsUsableTime) " ⚠" else ""
                        )
                    )
                }
                if (!batteryPlan.homeUsed) {
                    append("\n⚠ INÍCIO/Partida não definido; ida/retorno não entraram no cálculo.\n")
                }
            }

            append("\nFOTOGRAFIA\n")
            append("Os pontos mostrados no mapa são previsões. No KMZ o disparo ocorre por distância somente dentro das faixas, sem transformar cada foto em waypoint.\n")
        }

        AlertDialog.Builder(activity)
            .setTitle("Detalhes da missão")
            .setMessage(text.trim())
            .setPositiveButton("ENTENDI", null)
            .show()
    }

    private fun replaceBatteryGraphics() {
        val map = activity.findViewById<MapView>(R.id.map) ?: return
        val plan = host.currentMissionPlan() ?: run {
            clearProfessionalOverlays(map)
            return
        }
        val batteryPlan = calculateBatteryPlan(plan) ?: run {
            clearProfessionalOverlays(map)
            return
        }

        // Remove marcadores legados se uma versão anterior os tiver deixado no mapa.
        val legacy = map.overlays.filter { overlay ->
            when (overlay) {
                is Marker -> {
                    val title = overlay.title.orEmpty()
                    title.startsWith("Fim — Bateria") || title.startsWith("Início — Bateria")
                }
                is Polyline -> {
                    val title = overlay.title.orEmpty()
                    title.startsWith("Retorno Bateria") || title.startsWith("Home → início Bateria")
                }
                else -> false
            }
        }
        legacy.forEach { map.overlays.remove(it) }

        clearProfessionalOverlays(map)

        batteryPlan.parts.dropLast(1).forEachIndexed { index, part ->
            val end = part.lastOrNull() ?: return@forEachIndexed
            val from = index + 1
            val to = index + 2

            val marker = Marker(map).apply {
                position = GeoPoint(end.lat, end.lon)
                title = "Troca de bateria $from → $to"
                snippet = "Fim da Parte $from • RTH • troque a bateria • depois inicie a Parte $to"
                icon = transitionBadge(from, to)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { clicked, _ ->
                    clicked.showInfoWindow()
                    true
                }
            }
            professionalOverlays += marker
            map.overlays.add(marker)
        }

        map.invalidate()
    }

    private fun clearProfessionalOverlays(map: MapView) {
        professionalOverlays.forEach { map.overlays.remove(it) }
        professionalOverlays.clear()
    }

    private fun installMapGestureGuard() {
        val map = activity.findViewById<MapView>(R.id.map) ?: return
        if (map.overlays.any { it is UserZoomGuardOverlay }) return
        map.overlays.add(UserZoomGuardOverlay())
    }

    private inner class UserZoomGuardOverlay : Overlay() {
        private var pinch = false
        private var gestureSerial = 0

        override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pinch = false
                    gestureSerial++
                    mapView.controller.stopAnimation(false)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    pinch = true
                    mapView.controller.stopAnimation(false)
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount > 1) pinch = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pinch) {
                        val serial = gestureSerial
                        val center = GeoPoint(mapView.mapCenter.latitude, mapView.mapCenter.longitude)
                        val zoom = mapView.zoomLevelDouble
                        mapView.postDelayed({
                            if (serial == gestureSerial) {
                                mapView.controller.stopAnimation(false)
                                mapView.controller.setCenter(center)
                                mapView.controller.setZoom(zoom)
                                mapView.invalidate()
                            }
                        }, 180L)
                    }
                }
            }
            return false
        }
    }

    private fun calculateBatteryPlan(plan: MissionPlan): SmartMissionPlanner.BatteryPlan? {
        return runCatching {
            SmartMissionPlanner.splitByBattery(
                waypoints = plan.photoPoints,
                speedMs = plan.settings.speedMs,
                maxWaypointsPerMission = plan.settings.maxWaypointsPerMission,
                options = loadOptions(),
                home = host.preferredStartPoint(),
                surveyLines = plan.surveyLines
            )
        }.getOrNull()
    }

    private fun loadOptions(): SmartMissionPlanner.Options {
        return SmartMissionPlanner.Options(
            autoAltitudeFromGsd = prefs.getBoolean("auto_gsd", false),
            targetGsdCmPx = prefs.getFloat("target_gsd", 1.5f).toDouble(),
            nominalBatteryMinutes = prefs.getFloat("battery_minutes", 30f).toDouble(),
            reservePct = prefs.getFloat("reserve_pct", 25f).toDouble(),
            overlapPhotos = prefs.getInt("overlap_photos", 5)
        )
    }

    private fun transitionBadge(from: Int, to: Int): BitmapDrawable {
        val width = dp(58)
        val height = dp(25)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NAVY
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(8).toFloat(), dp(8).toFloat(), bg)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 193, 7)
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        canvas.drawRoundRect(
            dp(1).toFloat(),
            dp(1).toFloat(),
            width - dp(1).toFloat(),
            height - dp(1).toFloat(),
            dp(7).toFloat(),
            dp(7).toFloat(),
            border
        )

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(10).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val baseline = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText("B$from→B$to", width / 2f, baseline, textPaint)

        return BitmapDrawable(activity.resources, bitmap)
    }

    private fun formatArea(areaM2: Double): String {
        return when {
            areaM2 >= 1_000_000.0 -> String.format(Locale.getDefault(), "%.2f km²", areaM2 / 1_000_000.0)
            areaM2 >= 10_000.0 -> String.format(Locale.getDefault(), "%.2f ha", areaM2 / 10_000.0)
            else -> String.format(Locale.getDefault(), "%.0f m²", areaM2)
        }
    }

    private fun formatDistance(distanceM: Double): String {
        return if (distanceM >= 1000.0) {
            String.format(Locale.getDefault(), "%.2f km", distanceM / 1000.0)
        } else {
            String.format(Locale.getDefault(), "%.0f m", distanceM)
        }
    }

    private fun formatNumber(value: Double): String {
        return String.format(Locale.getDefault(), "%.1f", value)
            .removeSuffix(",0")
            .removeSuffix(".0")
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * activity.resources.displayMetrics.density

    companion object {
        private const val PREFS_NAME = "nv_smart_planning"
        private const val MISSION_CARD_TAG = "nv_professional_mission_card"
        private val NAVY = Color.rgb(13, 33, 55)
    }
}
