package com.nv.dronemapping.ui

import android.widget.LinearLayout

class PanelController(
    private val panelHost: PanelHost
) {

    fun show(panel: LinearLayout) {

        panelHost.showPanel(panel)
    }


    fun clear() {

        panelHost.clearPanel()
    }
}
