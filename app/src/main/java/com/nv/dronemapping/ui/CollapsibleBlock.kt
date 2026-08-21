package com.nv.dronemapping.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bloco expansível reutilizável para organizar opções
 * do NV Drone Mapping.
 */
open class CollapsibleBlock(
    context: Context,
    private val blockTitle: String
) : LinearLayout(context) {

    private val titleView: TextView
    private val contentContainer: LinearLayout
    private var expanded = false

    init {
        orientation = VERTICAL

        titleView = TextView(context).apply {
            text = "▶ $blockTitle"
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
            updateState()
        }
    }

    private fun updateState() {
        contentContainer.visibility =
            if (expanded) View.VISIBLE else View.GONE

        titleView.text =
            if (expanded)
                "▼ $blockTitle"
            else
                "▶ $blockTitle"
    }

    fun addContent(view: View) {
        contentContainer.addView(view)
    }

    fun open() {
        expanded = true
        updateState()
    }

    fun close() {
        expanded = false
        updateState()
    }
}
