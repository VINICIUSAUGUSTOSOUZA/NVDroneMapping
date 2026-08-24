package com.nv.dronemapping.ui

import android.content.Context

object PanelActions {

    fun openProjects(
        context: Context,
        controller: PanelController
    ) {
        controller.show(
            DronePanels.createProjectsPanel(context)
        )
    }


    fun openFlight(
        context: Context,
        controller: PanelController
    ) {
        controller.show(
            DronePanels.createFlightPanel(context)
        )
    }


    fun openCapture(
        context: Context,
        controller: PanelController
    ) {
        controller.show(
            DronePanels.createCapturePanel(context)
        )
    }


    fun openDJI(
        context: Context,
        controller: PanelController
    ) {
        controller.show(
            DronePanels.createDjiPanel(context)
        )
    }


    fun openAdvanced(
        context: Context,
        controller: PanelController
    ) {
        controller.show(
            DronePanels.createAdvancedPanel(context)
        )
    }


    fun openArea(
        context: Context,
        controller: PanelController
    ) {
        controller.show(
            DronePanels.createAreaPanel(context)
        )
    }
}
