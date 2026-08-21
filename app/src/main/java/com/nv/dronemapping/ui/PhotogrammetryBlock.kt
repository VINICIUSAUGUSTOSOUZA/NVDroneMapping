package com.nv.dronemapping.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bloco de Captura/Fotogrametria
 * Mantém configurações relacionadas às fotos da missão.
 */
class PhotogrammetryBlock(
    context: Context
) : CollapsibleBlock(
    context,
    "📷 Captura"
) {

    private val container: LinearLayout

    init {
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        addContent(container)

        addTextOption("Configurações de captura e fotogrametria")
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
