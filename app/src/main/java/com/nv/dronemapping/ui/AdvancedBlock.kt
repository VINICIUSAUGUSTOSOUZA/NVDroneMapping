package com.nv.dronemapping.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bloco Avancado
 * Organiza configuracoes adicionais do NV Drone Mapping.
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

    }


    fun addOption(view: View) {

        container.addView(view)

    }


    fun addTextOption(textValue: String) {

        val text = TextView(context).apply {

            text = textValue

            textSize = 14f

            setPadding(
                24,
                12,
                24,
                12
            )

        }

        container.addView(text)

    }
}
