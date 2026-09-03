package com.nv.dronemapping.ui

import com.nv.dronemapping.model.LatLng
import com.nv.dronemapping.model.MissionPlan

/**
 * Contrato explícito entre os controladores de missão e a Activity.
 * Evita reflection e listeners ocultos para ler/alterar estado privado.
 */
interface MissionUiHost {
    fun currentMissionPlan(): MissionPlan?
    fun replaceMissionPlan(plan: MissionPlan)
    fun preferredStartPoint(): LatLng?
    fun flightBoundaryCount(): Int
    fun regenerateMissionFromUi()
    fun exportMissionPlan(plan: MissionPlan)
}
