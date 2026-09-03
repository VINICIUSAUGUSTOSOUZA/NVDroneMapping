package com.nv.dronemapping.ui

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
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
import com.nv.dronemapping.model.MissionPlan
import com.nv.dronemapping.planning.SmartMissionPlanner
import java.util.Locale

/**
 * Planejamento inteligente com integração explícita à MainActivity.
 *
 * Não usa reflection, não substitui listeners de outros componentes e não toca
 * em campos privados da Activity. Toda troca de estado passa por MissionUiHost.
 */
class SmartPlanningController(
    private val activity: MainActivity,
    private val host: MissionUiHost
) {

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var options = loadOptions()
    private var lastBatteryPlan: SmartMissionPlanner.BatteryPlan? = null
    private var smartButton: Button? = null
    private var altitudeModeStatus: TextView? = null

    fun install() {
        installSmartPlanningButton()
        refreshSmartButton()
        refreshAltitudeModeUi()
    }

    /** Chamado explicitamente pela Activity imediatamente antes de ler os campos. */
    fun prepareBeforePlan() {
        if (!options.autoAltitudeFromGsd) return

        val altitude = runCatching {
            SmartMissionPlanner.altitudeForGsd(options.targetGsdCmPx)
        }.getOrNull() ?: return

        if (altitude in 10.0..500.0) {
            activity.findViewById<EditText>(R.id.inAltitude)
                ?.setText(formatNumber(altitude))
        }
    }

    /**
     * Aplica a divisão por bateria sobre um plano recém-gerado, privilegiando
     * finais de faixa e considerando os waypoints reais do DJI.
     */
    fun applyBatteryPlan(plan: MissionPlan): MissionPlan {
        val batteryPlan = calculateBatteryPlan(plan) ?: return plan
        lastBatteryPlan = batteryPlan

        return plan.copy(
            parts = batteryPlan.parts,
            stats = plan.stats.copy(partCount = batteryPlan.parts.size)
        )
    }

    /** Chamado depois que a Activity já desenhou/atualizou as estatísticas. */
    fun afterPlanRendered(plan: MissionPlan) {
        val batteryPlan = calculateBatteryPlan(plan)
        if (batteryPlan != null) {
            lastBatteryPlan = batteryPlan
            appendSmartStats(batteryPlan)
        }
        refreshSmartButton()
        refreshAltitudeModeUi()
    }

    fun setManualAltitudeMode() {
        if (!options.autoAltitudeFromGsd) {
            refreshAltitudeModeUi()
            return
        }

        options = options.copy(autoAltitudeFromGsd = false)
        saveOptions(options)
        refreshSmartButton()
        refreshAltitudeModeUi()
    }

    fun showExportSummary() {
        val current = host.currentMissionPlan() ?: return
        val updated = applyBatteryPlan(current)

        if (updated != current) {
            host.replaceMissionPlan(updated)
        }

        val batteryPlan = lastBatteryPlan ?: calculateBatteryPlan(updated)
        val totalBatteryMinutes = batteryPlan
            ?.infos
            ?.sumOf { it.estimatedSeconds }
            ?.div(60.0)

        val altitudeMode = if (options.autoAltitudeFromGsd) {
            "Automática por GSD (${formatNumber(options.targetGsdCmPx)} cm/px)"
        } else {
            "Manual"
        }

        val message = buildString {
            append("Modo de altura: $altitudeMode\n")
            append("Altura exportada: ${formatNumber(updated.settings.altitudeM)} m\n")
            append("Velocidade: ${formatNumber(updated.settings.speedMs)} m/s\n")
            append(String.format(Locale.getDefault(), "GSD: %.2f cm/pixel\n", updated.stats.gsdCmPx))
            append("Sobreposição: ${formatNumber(updated.settings.frontOverlapPct)}% frontal / ${formatNumber(updated.settings.sideOverlapPct)}% lateral\n")
            append("Fotos previstas: ${updated.stats.photoCount}\n")
            append("Faixas: ${updated.surveyLines.size}\n")

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
                            "Parte %d: fotos %d–%d | %d WP DJI | ~%.1f min%s\n",
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
                    append("\n⚠ Partida não definida: ida/retorno não entraram no cálculo.")
                }
            } else {
                append("Partes DJI: ${updated.parts.size}")
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Resumo antes de exportar")
            .setMessage(message.trim())
            .setPositiveButton("CONTINUAR") { _, _ ->
                host.exportMissionPlan(updated)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun showDjiGuide() {
        AlertDialog.Builder(activity)
            .setTitle("Levar a missão ao DJI Fly")
            .setMessage(
                "1. Importe KML/KMZ/DXF somente como referência, se quiser.\n\n" +
                    "2. Desenhe o QUADRO DE VOO e defina INÍCIO/Partida para incluir ida e retorno no cálculo.\n\n" +
                    "3. Em Voo > PLANEJAMENTO escolha claramente ALTURA MANUAL ou ALTURA AUTOMÁTICA POR GSD.\n\n" +
                    "4. Gere o plano. As bolinhas no mapa são posições previstas de foto; os waypoints DJI são somente a geometria das faixas.\n\n" +
                    "5. Durante cada faixa o disparo é por distância e o drone não deve parar em cada fotografia. As curvas usam passagem contínua quando há espaço geométrico seguro.\n\n" +
                    "6. Se a missão exceder a janela de bateria, o NV Mapping prefere encerrar a parte no final de uma faixa e repete algumas fotos na retomada.\n\n" +
                    "7. Exporte cada Parte DJI separadamente.\n\n" +
                    "8. No DJI Fly, crie/salve uma missão Waypoint temporária e substitua o KMZ pelo arquivo exportado.\n\n" +
                    "9. Reabra a missão e CONFIRA rota, altura, velocidade, RTH, gimbal e ações antes de decolar.\n\n" +
                    "10. Faça o primeiro teste em área aberta e pequena. Não use a primeira validação em produção."
            )
            .setPositiveButton("ENTENDI", null)
            .show()
    }

    private fun installSmartPlanningButton() {
        val panel = activity.findViewById<LinearLayout>(R.id.panelFlight) ?: return

        if (panel.findViewWithTag<View>(SMART_BUTTON_TAG) != null) return

        val button = Button(activity).apply {
            tag = SMART_BUTTON_TAG
            minHeight = 0
            minWidth = 0
            textSize = 9.0f
            isAllCaps = false
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { showPlanningDialog() }
        }

        button.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(38)
        ).apply {
            topMargin = dp(5)
        }

        panel.addView(button)
        smartButton = button
    }

    private fun installAltitudeModeStatus() {
        val altitude = activity.findViewById<EditText>(R.id.inAltitude) ?: return
        val parent = altitude.parent as? ViewGroup ?: return

        val existing = parent.findViewWithTag<TextView>(ALTITUDE_STATUS_TAG)
        if (existing != null) {
            altitudeModeStatus = existing
            return
        }

        val status = TextView(activity).apply {
            tag = ALTITUDE_STATUS_TAG
            textSize = 9.5f
            setPadding(0, dp(2), 0, dp(4))
        }

        val index = parent.indexOfChild(altitude)
        parent.addView(status, (index + 1).coerceAtMost(parent.childCount))
        altitudeModeStatus = status
    }

    private fun refreshAltitudeModeUi() {
        val altitude = activity.findViewById<EditText>(R.id.inAltitude) ?: return

        if (options.autoAltitudeFromGsd) {
            val calculated = runCatching {
                SmartMissionPlanner.altitudeForGsd(options.targetGsdCmPx)
            }.getOrNull()

            if (calculated != null && calculated in 10.0..500.0) {
                altitude.setText(formatNumber(calculated))
            }

            altitude.isEnabled = false
            altitude.alpha = 0.70f
            altitudeModeStatus?.apply {
                setTextColor(Color.rgb(0, 105, 92))
                text = if (calculated != null) {
                    "ALTURA AUTOMÁTICA POR GSD • ${formatNumber(options.targetGsdCmPx)} cm/px → ${formatNumber(calculated)} m • campo bloqueado"
                } else {
                    "ALTURA AUTOMÁTICA POR GSD • revise o GSD"
                }
            }
        } else {
            altitude.isEnabled = true
            altitude.alpha = 1.0f
            altitudeModeStatus?.apply {
                setTextColor(Color.rgb(55, 71, 79))
                text = "ALTURA MANUAL • o valor digitado acima será usado no planejamento e exportado ao DJI"
            }
        }
    }

    private fun showPlanningDialog() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(10))
        }

        val modePreview = TextView(activity).apply {
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
            paint.isFakeBoldText = true
        }
        root.addView(modePreview)

        val autoGsd = CheckBox(activity).apply {
            text = "Usar ALTURA AUTOMÁTICA calculada pelo GSD"
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
                    updateModePreview(modePreview, true, value)
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

        val info = TextView(activity).apply {
            setPadding(0, dp(8), 0, 0)
            textSize = 10.5f
            setTextColor(Color.DKGRAY)
            text =
                "No modo MANUAL, o campo de altura da tela principal fica liberado. " +
                    "No modo AUTOMÁTICO, o NV Mapping calcula e bloqueia esse campo para deixar claro qual altura será exportada."
        }
        root.addView(info)

        fun refreshPreview() {
            updateModePreview(
                modePreview,
                autoGsd.isChecked,
                parseDouble(gsd.second) ?: options.targetGsdCmPx
            )
        }

        autoGsd.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        refreshPreview()

        val scroll = ScrollView(activity).apply { addView(root) }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Planejamento")
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
                }

                options = newOptions
                saveOptions(newOptions)
                prepareBeforePlan()
                refreshSmartButton()
                refreshAltitudeModeUi()
                dialog.dismiss()

                if (host.flightBoundaryCount() >= 3) {
                    host.regenerateMissionFromUi()
                }
            }
        }

        dialog.show()
    }

    private fun updateModePreview(
        view: TextView,
        automatic: Boolean,
        targetGsd: Double
    ) {
        if (automatic) {
            val altitude = runCatching {
                SmartMissionPlanner.altitudeForGsd(targetGsd)
            }.getOrNull()
            view.setTextColor(Color.rgb(0, 105, 92))
            view.text = if (altitude != null) {
                "MODO: AUTOMÁTICO POR GSD → altura calculada ${formatNumber(altitude)} m"
            } else {
                "MODO: AUTOMÁTICO POR GSD"
            }
        } else {
            view.setTextColor(Color.rgb(55, 71, 79))
            view.text = "MODO: ALTURA MANUAL → será usado o valor digitado na tela principal"
        }
    }

    private fun calculateBatteryPlan(plan: MissionPlan): SmartMissionPlanner.BatteryPlan? {
        return runCatching {
            SmartMissionPlanner.splitByBattery(
                waypoints = plan.photoPoints,
                speedMs = plan.settings.speedMs,
                maxWaypointsPerMission = plan.settings.maxWaypointsPerMission,
                options = options,
                home = host.preferredStartPoint(),
                surveyLines = plan.surveyLines
            )
        }.getOrNull()
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

    private fun refreshSmartButton() {
        val mode = if (options.autoAltitudeFromGsd) {
            "AUTO GSD ${formatNumber(options.targetGsdCmPx)} cm"
        } else {
            "ALTURA MANUAL"
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

    private fun formatNumber(value: Double): String {
        val oneDecimal = String.format(Locale.US, "%.1f", value)
        return oneDecimal.removeSuffix(".0")
    }

    private fun density(): Float = activity.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density()).toInt()

    companion object {
        private const val PREFS_NAME = "nv_smart_planning"
        private const val SMART_BUTTON_TAG = "nv_smart_planning_button"
        private const val ALTITUDE_STATUS_TAG = "nv_altitude_mode_status"
    }
}
