package com.nv.dronemapping.ui

import android.content.Context
import android.widget.LinearLayout

class MenuPanelFactory {

    companion object {

        fun createFlight(context: Context): LinearLayout {
            return DronePanels.createFlightPanel(context)
        }

        fun createCapture(context: Context): LinearLayout {
            return DronePanels.createCapturePanel(context)
        }

        fun createDJI(context: Context): LinearLayout {
            return DronePanels.createDjiPanel(context)
        }
    }
}
