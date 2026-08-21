package com.nv.dronemapping.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bloco expansível reutilizável para organizar opções do NV Drone Mapping.
 * Inicialmente fechado e abre/fecha ao tocar no título.
 */
class CollapsibleBlock(
    context: Context,
    title: String
) : LinearLayout(context) {

    private val contentContainer: LinearLayout
    private val titleView: TextView
    private var expanded = false

    init {
        orientation = VERTICAL

        titleView = TextView(context).apply {
            text = "▶ $title"
            textSize = 16f
            setPadding(24, 20, 24, 20)
        }

        contentContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
        }

        addView(titleView)

        addView(contentContainer)

        titleView.setOnClickListener {
            expanded = !expanded

            contentContainer.visibility =
                if (expanded) View.VISIBLE else View.GONE

            titleView.text =
                if (expanded)
                    "▼ $title"
                else
                    "▶ $title"
        }
    }

    fun addContent(view: View) {
        contentContainer.addView(view)
    }

    fun open() {
        expanded = true
        contentContainer.visibility = View.VISIBLE
    }

    fun close() {
        expanded = false
        contentContainer.visibility = View.GONE
    }
}
