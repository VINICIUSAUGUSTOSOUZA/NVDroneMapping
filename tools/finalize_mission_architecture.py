from pathlib import Path
import re

path = Path("app/src/main/java/com/nv/dronemapping/MainActivity.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 trecho, encontrado {count}")
    text = text.replace(old, new, 1)


def replace_regex(pattern: str, replacement: str, label: str) -> None:
    global text
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 trecho regex, encontrado {count}")
    text = updated


replace_once(
    "import com.nv.dronemapping.ui.BottomNavigation\nimport com.nv.dronemapping.ui.PhotoPointsOverlay\n",
    "import com.nv.dronemapping.ui.BottomNavigation\n"
    "import com.nv.dronemapping.ui.PhotoPointsOverlay\n"
    "import com.nv.dronemapping.ui.MissionUiHost\n"
    "import com.nv.dronemapping.ui.SmartPlanningController\n"
    "import com.nv.dronemapping.ui.ProfessionalMissionController\n"
    "import com.nv.dronemapping.model.SurveyLine\n",
    "imports de missão",
)

replace_once(
    "class MainActivity : AppCompatActivity() {",
    "class MainActivity : AppCompatActivity(), MissionUiHost {",
    "implementação MissionUiHost",
)

replace_once(
    "    private lateinit var binding: ActivityMainBinding\n    private lateinit var store: ProjectStore\n",
    "    private lateinit var binding: ActivityMainBinding\n"
    "    private lateinit var store: ProjectStore\n"
    "    private lateinit var smartPlanningController: SmartPlanningController\n"
    "    private lateinit var professionalMissionController: ProfessionalMissionController\n",
    "campos dos controladores",
)

replace_once(
    "        setupActions()\n        setupSettingsBehavior()\n\n        updateBearingStatus()",
    "        setupActions()\n"
    "        setupSettingsBehavior()\n\n"
    "        smartPlanningController = SmartPlanningController(this, this)\n"
    "        professionalMissionController = ProfessionalMissionController(this, this)\n"
    "        smartPlanningController.install()\n"
    "        professionalMissionController.install()\n\n"
    "        updateBearingStatus()",
    "instalação explícita dos controladores",
)

replace_once(
    "                onDJI = {\n                    runCatching {\n                        showDjiGuide()\n                    }.onFailure {\n                        toast(\"Erro ao abrir DJI: ${it.message}\")\n                    }\n                },",
    "                onDJI = {\n"
    "                    runCatching {\n"
    "                        if (::smartPlanningController.isInitialized) {\n"
    "                            smartPlanningController.showDjiGuide()\n"
    "                        } else {\n"
    "                            showDjiGuide()\n"
    "                        }\n"
    "                    }.onFailure {\n"
    "                        toast(\"Erro ao abrir DJI: ${it.message}\")\n"
    "                    }\n"
    "                },",
    "navegação missão",
)

replace_once(
    "        binding.btnExport.setOnClickListener {\n\n            choosePartAndExport()\n        }",
    "        binding.btnExport.setOnClickListener {\n\n"
    "            if (::smartPlanningController.isInitialized) {\n"
    "                smartPlanningController.showExportSummary()\n"
    "            } else {\n"
    "                choosePartAndExport()\n"
    "            }\n"
    "        }",
    "botão exportar",
)

replace_once(
    "        binding.btnDjiGuide.setOnClickListener {\n\n            showDjiGuide()\n        }",
    "        binding.btnDjiGuide.setOnClickListener {\n\n"
    "            if (::smartPlanningController.isInitialized) {\n"
    "                smartPlanningController.showDjiGuide()\n"
    "            } else {\n"
    "                showDjiGuide()\n"
    "            }\n"
    "        }",
    "botão guia DJI",
)

invert_function = '''    private fun invertFlightDirection() {

        val currentPlan = plan

        if (currentPlan == null) {
            toast("Gere o plano de voo antes de inverter")
            return
        }

        val photoCount = currentPlan.photoPoints.size
        val reversedSurveyLines = currentPlan.surveyLines
            .asReversed()
            .map { line ->
                SurveyLine(
                    start = line.end,
                    end = line.start,
                    photoStartIndex = photoCount - 1 - line.photoEndIndex,
                    photoEndIndex = photoCount - 1 - line.photoStartIndex,
                    photoSpacingM = line.photoSpacingM
                )
            }

        val invertedBase = currentPlan.copy(
            waypoints = currentPlan.photoPoints.asReversed(),
            parts = currentPlan.parts.asReversed().map { it.asReversed() },
            surveyLines = reversedSurveyLines,
            routeWaypoints = reversedSurveyLines.flatMap { listOf(it.start, it.end) }
        )

        val invertedPlan = if (::smartPlanningController.isInitialized) {
            smartPlanningController.applyBatteryPlan(invertedBase)
        } else {
            invertedBase
        }

        replaceMissionPlan(invertedPlan)

        binding.txtHint.text =
            "Plano invertido. Saída, chegada, faixas e disparos foram invertidos."

        toast("Missão invertida: sentido, saída e chegada atualizados")
    }

'''

replace_regex(
    r"    private fun invertFlightDirection\(\) \{.*?\n    \}\n\n    private fun generateMission",
    invert_function + "    private fun generateMission",
    "função inverter",
)

generate_function = '''    private fun generateMission(
        showToast: Boolean = true
    ) {

        if (flightBoundary.size < 3) {
            toast("Desenhe o quadro de voo com pelo menos 3 vértices")
            return
        }

        if (::smartPlanningController.isInitialized) {
            smartPlanningController.prepareBeforePlan()
        }

        val settings = readSettings() ?: return

        runCatching {
            GridPlanner.plan(
                flightBoundary.toList(),
                settings
            )
        }.onSuccess { generated ->
            val oriented = GridPlanner.orientTowardStart(
                generated,
                preferredStart
            )

            val finalized = if (::smartPlanningController.isInitialized) {
                smartPlanningController.applyBatteryPlan(oriented)
            } else {
                oriented
            }

            replaceMissionPlan(finalized)

            binding.txtHint.text = if (finalized.parts.size > 1) {
                "Plano aplicado: ${finalized.parts.size} partes por bateria. Revise as faixas."
            } else {
                "Plano aplicado. Fotos por distância nas faixas; use -15° / +15° para rotacionar."
            }

            if (showToast) {
                toast("Plano contínuo aplicado dentro do quadro manual")
                showLongRouteWarningIfNeeded(finalized)
            }
        }.onFailure {
            toast(it.message ?: "Erro ao gerar missão")
        }
    }

'''

replace_regex(
    r"    private fun generateMission\(\n        showToast: Boolean = true\n    \) \{.*?\n    \}\n\n    private fun readSettings",
    generate_function + "    private fun readSettings",
    "função gerar missão",
)

replace_once(
    "    private fun apply2dPreset() {\n\n        binding.inAltitude.setText(",
    "    private fun apply2dPreset() {\n\n"
    "        if (::smartPlanningController.isInitialized) {\n"
    "            smartPlanningController.setManualAltitudeMode()\n"
    "        }\n\n"
    "        binding.inAltitude.setText(",
    "preset 2D manual",
)

replace_once(
    "    private fun apply3dPreset() {\n\n        binding.inAltitude.setText(",
    "    private fun apply3dPreset() {\n\n"
    "        if (::smartPlanningController.isInitialized) {\n"
    "            smartPlanningController.setManualAltitudeMode()\n"
    "        }\n\n"
    "        binding.inAltitude.setText(",
    "preset 3D manual",
)

replace_once(
    "        applySettings(\n            project.settings\n        )",
    "        if (::smartPlanningController.isInitialized) {\n"
    "            // Projetos persistem a altura resolvida. Ao abrir, ela vira manual\n"
    "            // para não ser sobrescrita por uma preferência global de GSD.\n"
    "            smartPlanningController.setManualAltitudeMode()\n"
    "        }\n\n"
    "        applySettings(\n"
    "            project.settings\n"
    "        )",
    "modo de altura ao carregar projeto",
)

old_load_block = '''        if (savedPlan != null) {
            plan = savedPlan
            binding.btnExport.isEnabled = true
            binding.btnShare.isEnabled = true
            binding.btnPreviewKml.isEnabled = true
            drawRoute(savedPlan)
            updateStats(savedPlan)
            updateBearingStatus(savedPlan.stats.effectiveBearingDeg)
            binding.txtHint.text = "Projeto e plano carregados"
        } else {
            updateBearingStatus()
            updateStatsAreaOnly()
        }
'''
new_load_block = '''        if (savedPlan != null && savedPlan.surveyLines.isNotEmpty()) {
            val finalized = if (::smartPlanningController.isInitialized) {
                smartPlanningController.applyBatteryPlan(savedPlan)
            } else {
                savedPlan
            }
            replaceMissionPlan(finalized)
            binding.txtHint.text = "Projeto carregado e compatibilizado com a missão contínua"
        } else if (flightBoundary.size >= 3) {
            // Segurança extra: ProjectStore já tenta migrar planos antigos. Se a
            // migração não foi possível, regeneramos pela UI antes de permitir exportação.
            generateMission(showToast = false)
            binding.txtHint.text = "Projeto antigo regenerado no formato de missão contínua"
        } else {
            updateBearingStatus()
            updateStatsAreaOnly()
        }
'''
replace_once(old_load_block, new_load_block, "carregamento de projeto")

replace_once(
    "            PhotoPointsOverlay(\n                plan.waypoints\n            ).also {",
    "            PhotoPointsOverlay(\n                plan.photoPoints\n            ).also {",
    "overlay de pontos de foto",
)

replace_once(
    "        binding.txtHint.text =\n            \"Toque no mapa para desenhar o QUADRO DE VOO\"\n    }",
    "        binding.txtHint.text =\n"
    "            \"Toque no mapa para desenhar o QUADRO DE VOO\"\n\n"
    "        if (::professionalMissionController.isInitialized) {\n"
    "            professionalMissionController.refreshNow()\n"
    "        }\n"
    "    }",
    "refresh ao invalidar plano",
)

host_methods = '''
    // MissionUiHost ---------------------------------------------------------
    override fun currentMissionPlan(): MissionPlan? = plan

    override fun replaceMissionPlan(plan: MissionPlan) {
        this.plan = plan

        binding.btnExport.isEnabled = true
        binding.btnShare.isEnabled = true
        binding.btnPreviewKml.isEnabled = true

        drawRoute(plan)
        updateStats(plan)
        updateBearingStatus(plan.stats.effectiveBearingDeg)

        if (::smartPlanningController.isInitialized) {
            smartPlanningController.afterPlanRendered(plan)
        }
        if (::professionalMissionController.isInitialized) {
            professionalMissionController.refreshNow()
        }
    }

    override fun preferredStartPoint(): LatLng? = preferredStart

    override fun flightBoundaryCount(): Int = flightBoundary.size

    override fun regenerateMissionFromUi() {
        generateMission(showToast = false)
    }

    override fun exportMissionPlan(plan: MissionPlan) {
        if (this.plan != plan) {
            replaceMissionPlan(plan)
        }
        choosePartAndExportInternal(plan)
    }

'''

replace_once(
    "    override fun onResume() {",
    host_methods + "    override fun onResume() {",
    "implementação do host",
)

# Falha de segurança: reflection de missão não pode reaparecer na Activity.
if "getDeclaredField(\"plan\")" in text or "getDeclaredMethod" in text:
    raise RuntimeError("reflection de missão ainda presente na MainActivity")

path.write_text(text, encoding="utf-8")
print("MainActivity integrada ao MissionUiHost com sucesso")
