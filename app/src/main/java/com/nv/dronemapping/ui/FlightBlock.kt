package com.nv.dronemapping.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bloco de planejamento de voo.
 * Mantém as opções de voo organizadas dentro do NV Drone Mapping.
 */
class FlightBlock(
    context: Context
) : CollapsibleBlock(
    context,
    "✈️ Planejamento de Voo"
) {

    private val optionsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    init {
        addContent(optionsContainer)
    }

    fun addOption(view: View) {
        optionsContainer.addView(view)
    }

    fun addTextOption(label: String) {
        val text = TextView(context).apply {
            this.text = label
            textSize = 14f
            setPadding(24, 12, 24, 12)
        }

        optionsContainer.addView(text)
    }
}
