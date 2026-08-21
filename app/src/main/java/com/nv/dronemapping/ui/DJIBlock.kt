package com.nv.dronemapping.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bloco DJI
 * Organiza configuracoes especificas do drone e exportacao de missao.
 */
class DJIBlock(
    context: Context
) : CollapsibleBlock(
    context,
    "🚁 DJI"
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
