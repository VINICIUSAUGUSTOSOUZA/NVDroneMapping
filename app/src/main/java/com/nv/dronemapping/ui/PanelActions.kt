package com.nv.dronemapping.ui

import android.view.ViewGroup

class PanelActions(
    private val host: PanelHost,
    private val parent: ViewGroup
) {

    fun openFlight() {
        host.show(
            MenuPanelFactory.createFlight(parent.context)
        )
    }

    fun openCapture() {
        host.show(
            MenuPanelFactory.createCapture(parent.context)
        )
    }

    fun openDJI() {
        host.show(
            MenuPanelFactory.createDJI(parent.context)
        )
    }

    fun close() {
        host.hide()
    }
}
