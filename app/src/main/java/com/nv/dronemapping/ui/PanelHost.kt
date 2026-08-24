package com.nv.dronemapping.ui

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout

class PanelHost(
    context: Context
) : FrameLayout(context) {

    private var currentPanel: LinearLayout? = null

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
    }


    fun showPanel(panel: LinearLayout) {

        removeAllViews()

        currentPanel = panel

        addView(
            panel,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        )
    }


    fun clearPanel() {

        removeAllViews()

        currentPanel = null
    }
}
