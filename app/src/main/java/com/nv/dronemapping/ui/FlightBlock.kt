package com.nv.dronemapping.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bloco de Planejamento de Voo
 * Mantém as configurações principais da missão.
 */
class FlightBlock(
    context: Context
) : CollapsibleBlock(
    context,
    "✈️ Voo"
) {

    private val container: LinearLayout

    init {
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        addContent(container)

        addTextOption("Configurações de planejamento de voo")
    }

    fun addOption(view: View) {
        container.addView(view)
    }

    fun addTextOption(textValue: String) {
        val text = TextView(context).apply {
            text = textValue
            textSize = 14f
            setPadding(24, 12, 24, 12)
        }

        container.addView(text)
    }
}
