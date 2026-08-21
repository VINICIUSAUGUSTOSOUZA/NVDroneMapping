package com.nv.dronemapping.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bloco Avançado
 * Mantém configurações técnicas que não ficam na tela principal.
 */
class AdvancedBlock(
    context: Context
) : CollapsibleBlock(
    context,
    "⚙️ Avançado"
) {

    private val container: LinearLayout

    init {
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        addContent(container)

        addTextOption("Direção das linhas: AUTO / Manual")
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
