package com.nv.dronemapping.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.nv.dronemapping.MainActivity
import com.nv.dronemapping.R
import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.MissionPlan
import com.nv.dronemapping.planning.SmartMissionPlanner
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

/**
 * Camada aditiva do planejamento inteligente.
 *
 * Não substitui GridPlanner, armazenamento de projetos ou exportador DJI.
 * Trabalha sobre a rota já gerada pelo app e reorganiza somente as partes DJI
 * para que cada parte caiba na janela segura de uma bateria.
 */
class SmartPlanningController(
    private val activity: MainActivity
) {

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var options = loadOptions()
    private var lastBatteryPlan: SmartMissionPlanner.BatteryPlan? = null
    private val batteryOverlays = mutableListOf<Overlay>()
    private var smartButton: Button? = null
    private var selectingStartAtTouch = false

    fun install() {
        installSmartPlanningButton()
        installPlanningHooks()
        installExportSummary()
        installDjiGuide()
    }

    private fun installSmartPlanningButton() {
        val panel = activity.findViewById<LinearLayout>(R.id.panelFlight) ?: return

        if (panel.findViewWithTag<View>(SMART_BUTTON_TAG) != null) {
            return
        }

        val button = Button(activity).apply {
            tag = SMART_BUTTON_TAG
            minHeight = 0
            minWidth = 0
            textSize = 9.0f
            isAllCaps = false
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                showPlanningDialog()
            }
        }

        button.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(38)
        ).apply {
            topMargin = dp(5)
        }

        panel.addView(button)
        smartButton = button
        refreshSmartButton()
    }

    private fun installPlanningHooks() {
        listOf(
            R.id.btnGenerate,
            R.id.btnRotateLeft,
            R.id.btnRotateRight,
            R.id.btnInvert
        ).forEach { id ->
            activity.findViewById<View>(id)?.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> prepareBeforePlan()
                    MotionEvent.ACTION_UP -> view.post { applyBatteryPlanToCurrentMission() }
                }
                false
            }
        }

        // Quando o usuário define a Partida do drone pelo mapa, o MainActivity
        // regenera a missão no mesmo toque. Só reagimos se esse modo estava ativo.
        activity.findViewById<MapView>(R.id.map)?.setOnTouchListener { map, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selectingStartAtTouch = readPrivateBoolean("selectingStartPoint")
                    if (selectingStartAtTouch) {
                        prepareBeforePlan()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (selectingStartAtTouch) {
                        selectingStartAtTouch = false
                        map.post { applyBatteryPlanToCurrentMission() }
                    }
                }
            }
            false
        }
    }

    private fun showPlanningDialog() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(10))
        }

        val autoGsd = CheckBox(activity).apply {
            text = "Calcular altura automaticamente pelo GSD"
            isChecked = options.autoAltitudeFromGsd
        }
        root.addView(autoGsd)

        root.addView(sectionTitle("Qualidade / GSD"))

        val gsd = numericField(
            "GSD desejado (cm/pixel)",
            formatNumber(options.targetGsdCmPx)
        )
        root.addView(gsd.first)

        val presetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun addPreset(label: String, value: Double) {
            val b = Button(activity).apply {
                text = label
                textSize = 9f
                minHeight = 0
                minWidth = 0
                setOnClickListener {
                    autoGsd.isChecked = true
                    gsd.second.setText(formatNumber(value))
                }
            }
            presetRow.addView(
                b,
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            )
        }

        addPreset("1,0 cm\nAlta", 1.0)
        addPreset("1,5 cm\nPadrão", 1.5)
        addPreset("2,5 cm\nGeral", 2.5)
        root.addView(presetRow)

        root.addView(sectionTitle("Bateria / Segurança"))

        val battery = numericField(
            "Autonomia estimada da bateria (min)",
            formatNumber(options.nominalBatteryMinutes)
        )
        val reserve = numericField(
            "Reserva mínima (%)",
            formatNumber(options.reservePct)
        )
        val overlap = numericField(
            "Fotos repetidas entre baterias",
            options.overlapPhotos.toString(),
            decimal = false
        )

        root.addView(battery.first)
        root.addView(reserve.first)
        root.addView(overlap.first)

        val currentAltitude = activity.findViewById<EditText>(R.id.inAltitude)
            ?.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
            ?: 60.0

        val info = TextView(activity).apply {
            setPadding(0, dp(8), 0, 0)
            textSize = 11f
            setTextColor(Color.DKGRAY)
            text = buildString {
                append("Altura manual atual: ${formatNumber(currentAltitude)} m. ")
                append("Se o modo automático estiver desligado, essa altura continua valendo.\n")
                append("A divisão considera ida até a rota, levantamento, fotos, retorno ao ponto de partida e margem de segurança.")
            }
        }
        root.addView(info)

        val scroll = ScrollView(activity).apply {
            addView(root)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Planejamento inteligente")
            .setView(scroll)
            .setPositiveButton("APLICAR", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val targetGsd = parseDouble(gsd.second)
                val nominalMinutes = parseDouble(battery.second)
                val reservePct = parseDouble(reserve.second)
                val repeatPhotos = overlap.second.text?.toString()?.trim()?.toIntOrNull()

                if (targetGsd == null || targetGsd !in 0.2..10.0) {
                    gsd.second.error = "Use um GSD entre 0,2 e 10 cm/pixel"
                    return@setOnClickListener
                }
                if (nominalMinutes == null || nominalMinutes !in 5.0..120.0) {
                    battery.second.error = "Use uma autonomia entre 5 e 120 minutos"
                    return@setOnClickListener
                }
                if (reservePct == null || reservePct !in 5.0..50.0) {
                    reserve.second.error = "Use uma reserva entre 5% e 50%"
                    return@setOnClickListener
                }
                if (repeatPhotos == null || repeatPhotos !in 0..20) {
                    overlap.second.error = "Use de 0 a 20 fotos"
                    return@setOnClickListener
                }

                val newOptions = SmartMissionPlanner.Options(
                    autoAltitudeFromGsd = autoGsd.isChecked,
                    targetGsdCmPx = targetGsd,
                    nominalBatteryMinutes = nominalMinutes,
                    reservePct = reservePct,
                    overlapPhotos = repeatPhotos
                )

                if (newOptions.autoAltitudeFromGsd) {
                    val altitude = SmartMissionPlanner.altitudeForGsd(newOptions.targetGsdCmPx)
                    if (altitude !in 10.0..500.0) {
                        gsd.second.error = "Esse GSD gera altura fora do limite aceito pelo app"
                        return@setOnClickListener
                    }
                    activity.findViewById<EditText>(R.id.inAltitude)
                        ?.setText(formatNumber(altitude))
                }

                options = newOptions
                saveOptions(newOptions)
                refreshSmartButton()
                dialog.dismiss()

                // Se já existe quadro de voo, atualiza imediatamente a missão.
                if (flightBoundarySize() >= 3) {
                    prepareBeforePlan()
                    activity.findViewById<View>(R.id.btnGenerate)?.performClick()
                    activity.findViewById<View>(R.id.btnGenerate)?.post {
                        applyBatteryPlanToCurrentMission()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun prepareBeforePlan() {
        if (!options.autoAltitudeFromGsd) {
            return
        }

        val altitude = runCatching {
            SmartMissionPlanner.altitudeForGsd(options.targetGsdCmPx)
        }.getOrNull() ?: return

        if (altitude in 10.0..500.0) {
            activity.findViewById<EditText>(R.id.inAltitude)
                ?.setText(formatNumber(altitude))
        }
    }

    private fun applyBatteryPlanToCurrentMission() {
        val current = currentPlan() ?: run {
            clearBatteryOverlays()
            return
        }

        val home = preferredStart()
        val batteryPlan = runCatching {
            SmartMissionPlanner.splitByBattery(
                waypoints = current.waypoints,
                speedMs = current.settings.speedMs,
                maxWaypointsPerMission = current.settings.maxWaypointsPerMission,
                options = options,
                home = home
            )
        }.getOrNull() ?: return

        val updated = current.copy(
            parts = batteryPlan.parts,
            stats = current.stats.copy(
                partCount = batteryPlan.parts.size
            )
        )

        setCurrentPlan(updated)
        lastBatteryPlan = batteryPlan

        invokePrivate(
            "drawRoute",
            arrayOf(MissionPlan::class.java),
            arrayOf(updated)
        )
        invokePrivate(
            "updateStats",
            arrayOf(MissionPlan::class.java),
            arrayOf(updated)
        )
        invokePrivate(
            "updateBearingStatus",
            arrayOf(Double::class.javaObjectType),
            arrayOf(updated.stats.effectiveBearingDeg)
        )

        appendSmartStats(batteryPlan)
        drawBatteryOverlays(batteryPlan, home)

        activity.findViewById<TextView>(R.id.txtHint)?.text =
            if (batteryPlan.batteryCount > 1) {
                "Plano aplicado: ${batteryPlan.batteryCount} partes por bateria. Cada parte termina com RTH."
            } else {
                "Plano aplicado: missão cabe em 1 bateria pela margem configurada."
            }
    }

    private fun appendSmartStats(
        batteryPlan: SmartMissionPlanner.BatteryPlan
    ) {
        val stats = activity.findViewById<TextView>(R.id.txtStats) ?: return
        val usableMinutes = batteryPlan.usableSeconds / 60.0

        stats.append(
            String.format(
                Locale.getDefault(),
                "\nBaterias: %d | útil: %.1f min/bat | reserva: %.0f%%",
                batteryPlan.batteryCount,
                usableMinutes,
                batteryPlan.reservePct
            )
        )

        if (!batteryPlan.homeUsed) {
            stats.append("\n⚠ Defina INÍCIO/Partida para incluir ida e retorno no cálculo da bateria.")
        }

        if (batteryPlan.infos.any { it.exceedsUsableTime }) {
            stats.append("\n⚠ Existe parte acima da janela útil configurada. Revise autonomia/velocidade.")
        }
    }

    private fun drawBatteryOverlays(
        batteryPlan: SmartMissionPlanner.BatteryPlan,
        home: LatLng?
    ) {
        clearBatteryOverlays()
        val map = activity.findViewById<MapView>(R.id.map) ?: return

        batteryPlan.parts.forEachIndexed { index, part ->
            if (part.isEmpty()) return@forEachIndexed

            val end = part.last()
            val endMarker = Marker(map).apply {
                position = GeoPoint(end.lat, end.lon)
                title = "Fim — Bateria ${index + 1}"
                snippet = "Após esta parte: Return to Home"
                icon = batteryDot(Color.rgb(211, 47, 47))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            batteryOverlays += endMarker
            map.overlays.add(endMarker)

            if (index > 0) {
                val start = part.first()
                val startMarker = Marker(map).apply {
                    position = GeoPoint(start.lat, start.lon)
                    title = "Início — Bateria ${index + 1}"
                    snippet = "Retomada com sobreposição de fotos"
                    icon = batteryDot(Color.rgb(46, 125, 50))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                batteryOverlays += startMarker
                map.overlays.add(startMarker)
            }

            if (home != null) {
                val returnLine = Polyline(map).apply {
                    setPoints(
                        listOf(
                            GeoPoint(end.lat, end.lon),
                            GeoPoint(home.lat, home.lon)
                        )
                    )
                    outlinePaint.color = Color.rgb(211, 47, 47)
                    outlinePaint.strokeWidth = 3f
                    outlinePaint.pathEffect = DashPathEffect(
                        floatArrayOf(12f * density(), 8f * density()),
                        0f
                    )
                    title = "Retorno Bateria ${index + 1} → Home"
                }
                batteryOverlays += returnLine
                map.overlays.add(returnLine)

                if (index > 0) {
                    val start = part.first()
                    val outboundLine = Polyline(map).apply {
                        setPoints(
                            listOf(
                                GeoPoint(home.lat, home.lon),
                                GeoPoint(start.lat, start.lon)
                            )
                        )
                        outlinePaint.color = Color.rgb(46, 125, 50)
                        outlinePaint.strokeWidth = 3f
                        outlinePaint.pathEffect = DashPathEffect(
                            floatArrayOf(10f * density(), 8f * density()),
                            0f
                        )
                        title = "Home → início Bateria ${index + 1}"
                    }
                    batteryOverlays += outboundLine
                    map.overlays.add(outboundLine)
                }
            }
        }

        map.invalidate()
    }

    private fun clearBatteryOverlays() {
        val map = activity.findViewById<MapView>(R.id.map) ?: return
        batteryOverlays.forEach { map.overlays.remove(it) }
        batteryOverlays.clear()
        map.invalidate()
    }

    private fun installExportSummary() {
        activity.findViewById<View>(R.id.btnExport)?.setOnClickListener {
            val current = currentPlan() ?: return@setOnClickListener

            // Garante que a exportação use a divisão inteligente mais recente.
            applyBatteryPlanToCurrentMission()
            val plan = currentPlan() ?: current
            val batteryPlan = lastBatteryPlan

            val totalBatteryMinutes = batteryPlan
                ?.infos
                ?.sumOf { it.estimatedSeconds }
                ?.div(60.0)

            val message = buildString {
                append("Altura: ${formatNumber(plan.settings.altitudeM)} m\n")
                append(String.format(Locale.getDefault(), "GSD: %.2f cm/pixel\n", plan.stats.gsdCmPx))
                append("Sobreposição: ${formatNumber(plan.settings.frontOverlapPct)}% frontal / ${formatNumber(plan.settings.sideOverlapPct)}% lateral\n")
                append("Fotos: ${plan.stats.photoCount}\n")

                if (batteryPlan != null) {
                    append("Baterias/partes: ${batteryPlan.batteryCount}\n")
                    append(String.format(Locale.getDefault(), "Reserva planejada: %.0f%%\n", batteryPlan.reservePct))
                    append(String.format(Locale.getDefault(), "Janela útil: %.1f min por bateria\n", batteryPlan.usableSeconds / 60.0))
                    if (totalBatteryMinutes != null) {
                        append(String.format(Locale.getDefault(), "Tempo operacional estimado: %.1f min\n", totalBatteryMinutes))
                    }
                    batteryPlan.infos.forEach { info ->
                        append(
                            String.format(
                                Locale.getDefault(),
                                "Parte %d: fotos %d–%d | ~%.1f min%s\n",
                                info.partNumber,
                                info.startPhotoNumber,
                                info.endPhotoNumber,
                                info.estimatedSeconds / 60.0,
                                if (info.exceedsUsableTime) " ⚠" else ""
                            )
                        )
                    }
                    if (!batteryPlan.homeUsed) {
                        append("\n⚠ Partida não definida: ida/retorno não entraram no cálculo.")
                    }
                } else {
                    append("Partes DJI: ${plan.parts.size}")
                }
            }

            AlertDialog.Builder(activity)
                .setTitle("Resumo antes de exportar")
                .setMessage(message.trim())
                .setPositiveButton("CONTINUAR") { _, _ ->
                    val ok = invokePrivate(
                        "choosePartAndExportInternal",
                        arrayOf(MissionPlan::class.java),
                        arrayOf(plan)
                    )
                    if (!ok) {
                        invokePrivate("choosePartAndExport", emptyArray(), emptyArray())
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun installDjiGuide() {
        activity.findViewById<View>(R.id.btnDjiGuide)?.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Levar a missão ao DJI Fly")
                .setMessage(
                    "1. Importe KML/KMZ/DXF apenas como referência visual, se quiser.\n\n" +
                        "2. Desenhe o QUADRO DE VOO e defina INÍCIO/Partida do drone para o cálculo considerar ida e retorno.\n\n" +
                        "3. Em Voo > PLANEJAMENTO, escolha GSD/altura e configure autonomia, reserva e repetição entre baterias.\n\n" +
                        "4. Toque em APLICAR PLANO. Se a missão passar da janela segura, o NV Mapping cria Parte 1, Parte 2, Parte 3... automaticamente.\n\n" +
                        "5. Revise no mapa os pontos de fim/início de cada bateria e as linhas de retorno ao Home.\n\n" +
                        "6. Exporte cada Parte DJI separadamente. Cada Parte corresponde a uma etapa independente de voo.\n\n" +
                        "7. No DJI Fly, crie/salve uma missão Waypoint temporária para a parte que será executada e substitua o KMZ pelo arquivo exportado pelo NV Mapping.\n\n" +
                        "8. Reabra a missão no DJI Fly e CONFIRA rota, altura, RTH, gimbal e ações de foto antes de iniciar.\n\n" +
                        "9. Execute a Parte 1. Ao terminar, a ação final permanece RTH. Espere o pouso e troque fisicamente a bateria.\n\n" +
                        "10. Para continuar, carregue a Parte 2 no DJI Fly, confira novamente e inicie. Ela começa algumas fotos antes do fim anterior para manter cobertura. Repita nas demais partes.\n\n" +
                        "11. O Low Battery RTH da DJI continua sendo uma proteção adicional; não espere a bateria crítica para encerrar uma parte planejada.\n\n" +
                        "Faça o primeiro teste em área aberta e pequena."
                )
                .setPositiveButton("ENTENDI", null)
                .show()
        }
    }

    private fun currentPlan(): MissionPlan? {
        return runCatching {
            val field = MainActivity::class.java.getDeclaredField("plan")
            field.isAccessible = true
            field.get(activity) as? MissionPlan
        }.getOrNull()
    }

    private fun setCurrentPlan(plan: MissionPlan) {
        runCatching {
            val field = MainActivity::class.java.getDeclaredField("plan")
            field.isAccessible = true
            field.set(activity, plan)
        }
    }

    private fun preferredStart(): LatLng? {
        return runCatching {
            val field = MainActivity::class.java.getDeclaredField("preferredStart")
            field.isAccessible = true
            field.get(activity) as? LatLng
        }.getOrNull()
    }

    private fun flightBoundarySize(): Int {
        return runCatching {
            val field = MainActivity::class.java.getDeclaredField("flightBoundary")
            field.isAccessible = true
            (field.get(activity) as? Collection<*>)?.size ?: 0
        }.getOrDefault(0)
    }

    private fun readPrivateBoolean(name: String): Boolean {
        return runCatching {
            val field = MainActivity::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.getBoolean(activity)
        }.getOrDefault(false)
    }

    private fun invokePrivate(
        name: String,
        types: Array<Class<*>>,
        args: Array<Any?>
    ): Boolean {
        return runCatching {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types)
            method.isAccessible = true
            method.invoke(activity, *args)
        }.isSuccess
    }

    private fun refreshSmartButton() {
        val mode = if (options.autoAltitudeFromGsd) {
            "GSD ${formatNumber(options.targetGsdCmPx)} cm"
        } else {
            "Altura manual"
        }
        smartButton?.text =
            "PLANEJAMENTO • $mode • Bat ${formatNumber(options.nominalBatteryMinutes)} min • Reserva ${formatNumber(options.reservePct)}%"
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

    private fun saveOptions(value: SmartMissionPlanner.Options) {
        prefs.edit()
            .putBoolean("auto_gsd", value.autoAltitudeFromGsd)
            .putFloat("target_gsd", value.targetGsdCmPx.toFloat())
            .putFloat("battery_minutes", value.nominalBatteryMinutes.toFloat())
            .putFloat("reserve_pct", value.reservePct.toFloat())
            .putInt("overlap_photos", value.overlapPhotos)
            .apply()
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.rgb(13, 33, 55))
            setPadding(0, dp(10), 0, dp(4))
            paint.isFakeBoldText = true
        }
    }

    private fun numericField(
        hint: String,
        value: String,
        decimal: Boolean = true
    ): Pair<LinearLayout, EditText> {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val label = TextView(activity).apply {
            text = hint
            textSize = 10f
            setTextColor(Color.DKGRAY)
        }
        val input = EditText(activity).apply {
            setText(value)
            textSize = 13f
            minHeight = dp(42)
            inputType = if (decimal) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
        }
        container.addView(label)
        container.addView(input)
        return container to input
    }

    private fun parseDouble(edit: EditText): Double? {
        return edit.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
    }

    private fun batteryDot(color: Int): BitmapDrawable {
        val size = dp(22)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, dp(6).toFloat(), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, dp(6).toFloat(), paint)
        return BitmapDrawable(activity.resources, bitmap)
    }

    private fun formatNumber(value: Double): String {
        val oneDecimal = String.format(Locale.US, "%.1f", value)
        return oneDecimal.removeSuffix(".0")
    }

    private fun density(): Float = activity.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density()).toInt()

    companion object {
        private const val PREFS_NAME = "nv_smart_planning"
        private const val SMART_BUTTON_TAG = "nv_smart_planning_button"
    }
}
