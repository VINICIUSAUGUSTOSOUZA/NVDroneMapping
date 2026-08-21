package com.nv.dronemapping.ui

import android.view.View
import android.view.ViewGroup

class PanelController(
    private val container: ViewGroup
) {

    private var currentPanel: View? = null

    fun open(panel: View) {

        close()

        currentPanel = panel

        container.addView(panel)

        panel.visibility = View.VISIBLE
    }

    fun close() {

        currentPanel?.let { view ->

            container.removeView(view)
        }

        currentPanel = null
    }

    fun toggle(panel: View) {

        if (currentPanel == panel) {
            close()
        } else {
            open(panel)
        }
    }
}
