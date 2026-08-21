package com.nv.dronemapping.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class BottomNavigation(
    context: Context,
    private val onProject: () -> Unit,
    private val onFlight: () -> Unit,
    private val onCapture: () -> Unit,
    private val onDJI: () -> Unit,
    private val onAdvanced: () -> Unit,
    private val onArea: () -> Unit
) : LinearLayout(context) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(20, 20, 20))

        addItem("📁", "Projetos", onProject)
        addItem("✈️", "Voo", onFlight)
        addItem("📷", "Foto", onCapture)
        addItem("🚁", "DJI", onDJI)
        addItem("⚙️", "Avançado", onAdvanced)
        addItem("🗺️", "Área", onArea)
    }

    private fun addItem(icon: String, title: String, action: () -> Unit) {

        val item = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
            setOnClickListener { action() }
        }

        val iconView = TextView(context).apply {
            text = icon
            textSize = 22f
            gravity = Gravity.CENTER
        }

        val titleView = TextView(context).apply {
            text = title
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
        }

        item.addView(iconView)
        item.addView(titleView)

        addView(
            item,
            LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        )
    }
}
